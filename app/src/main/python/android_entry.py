"""
Точка входа для Android-приложения (Chaquopy).

Запускает оригинальное ядро Flowseal/tg-ws-proxy (папка proxy/) в отдельном
потоке и даёт Kotlin-коду две функции: run() и stop().
"""
import asyncio
import gc
import logging
import threading
import time
from asyncio.selector_events import _SelectorSocketTransport
from logging.handlers import RotatingFileHandler

from proxy import proxy_config
from proxy.config import parse_dc_ip_list
from proxy import tg_ws_proxy as _core
from proxy.tg_ws_proxy import _run
from proxy.utils import DomainCensorFilter

_lock = threading.Lock()
_state = {"loop": None, "stop": None, "want_stop": False}
_finished = threading.Event()   # выставлен, когда run() не выполняется
_finished.set()

# В Python 3.12 Server.wait_closed() ждёт закрытия ВСЕХ клиентских соединений.
# Если обработчик клиента завершился (или был отменён), не закрыв сокет,
# остановка прокси зависает. Поэтому всегда закрываем writer в конце.
_orig_handle_client = _core._handle_client


async def _tracked_handle_client(reader, writer, secret):
    try:
        return await _orig_handle_client(reader, writer, secret)
    finally:
        try:
            writer.close()
        except Exception:
            pass


_core._handle_client = _tracked_handle_client


def _abort_transports():
    """Рвёт все клиентские соединения слушающего сокета (внутри event loop).

    Нужно для соединений, принятых в момент остановки: их обработчик мог
    не успеть стартовать и закрыть сокет, а wait_closed() ждёт их вечно.
    """
    srv = getattr(_core, "_server_instance", None)
    if srv is None:
        return
    try:
        for obj in gc.get_objects():
            if isinstance(obj, _SelectorSocketTransport) \
                    and getattr(obj, "_server", None) is srv:
                try:
                    obj.abort()
                except Exception:
                    pass
    except Exception:
        pass
_logging_ready = False


def _setup_logging(log_file):
    global _logging_ready
    if _logging_ready:
        return
    fmt = logging.Formatter("%(asctime)s  %(levelname)-5s  %(message)s",
                            datefmt="%H:%M:%S")
    root = logging.getLogger()
    root.setLevel(logging.INFO)

    fh = RotatingFileHandler(log_file, maxBytes=1024 * 1024, backupCount=1,
                             encoding="utf-8")
    fh.setFormatter(fmt)
    fh.addFilter(DomainCensorFilter())
    root.addHandler(fh)

    # Chaquopy пересылает stderr в logcat
    sh = logging.StreamHandler()
    sh.setFormatter(fmt)
    sh.addFilter(DomainCensorFilter())
    root.addHandler(sh)

    for noisy in ("asyncio", "httpx", "httpcore"):
        logging.getLogger(noisy).setLevel(logging.WARNING)
    _logging_ready = True


def run(host, port, secret, dc_ips, log_file, pool_size=4):
    """Блокирующий запуск прокси. Возвращается после stop() или при ошибке."""
    _setup_logging(log_file)
    log = logging.getLogger("tg-mtproto-proxy")

    proxy_config.host = host
    proxy_config.port = int(port)
    proxy_config.secret = secret
    proxy_config.dc_redirects = parse_dc_ip_list(
        [s.strip() for s in dc_ips.split(",") if s.strip()])
    proxy_config.buffer_size = 256 * 1024
    proxy_config.pool_size = int(pool_size)
    proxy_config.fallback_cfproxy = True
    proxy_config.cfproxy_user_domains = []
    proxy_config.cfproxy_worker_domains = []
    proxy_config.cfproxy_h2_media = True
    proxy_config.disable_secure = False
    proxy_config.fake_tls_domain = ""
    proxy_config.proxy_protocol = False
    proxy_config.force_test_dc = False

    with _lock:
        _state["want_stop"] = False
    _finished.clear()

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)

    async def main():
        stop = asyncio.Event()
        with _lock:
            _state["loop"] = loop
            _state["stop"] = stop
            if _state["want_stop"]:
                return
        await _run(stop)

    try:
        loop.run_until_complete(main())
    except Exception:
        log.exception("Proxy crashed")
        raise
    finally:
        with _lock:
            _state["loop"] = None
            _state["stop"] = None
        # гарантированно освобождаем порт, даже если остановка была принудительной
        try:
            srv = getattr(_core, "_server_instance", None)
            if srv is not None:
                srv.close()
        except Exception:
            pass
        try:
            loop.run_until_complete(loop.shutdown_asyncgens())
        except Exception:
            pass
        try:
            loop.close()
        except Exception:
            pass
        _finished.set()


def _force_cancel():
    """Отменяет все задачи (выполняется внутри event loop)."""
    _abort_transports()
    for task in asyncio.all_tasks():
        task.cancel()
    srv = getattr(_core, "_server_instance", None)
    if srv is not None:
        srv.close()


def stop(timeout=4.0):
    """Останавливает прокси и ждёт завершения run().

    Сначала мягкая остановка; если за `timeout` секунд не завершился
    (например, задача подключения проглотила отмену) - принудительная.
    """
    with _lock:
        _state["want_stop"] = True
        loop, ev = _state["loop"], _state["stop"]
    if loop is None or ev is None:
        _finished.wait(timeout)
        return
    log = logging.getLogger("tg-mtproto-proxy")
    try:
        loop.call_soon_threadsafe(ev.set)
    except RuntimeError:
        return
    if _finished.wait(0.4):
        return
    try:
        loop.call_soon_threadsafe(_abort_transports)
    except RuntimeError:
        return
    if _finished.wait(timeout):
        return

    log.warning("Graceful stop timed out, forcing")
    deadline = time.monotonic() + 6
    while not _finished.wait(0.5) and time.monotonic() < deadline:
        try:
            loop.call_soon_threadsafe(_force_cancel)
        except RuntimeError:
            break
    if not _finished.is_set():
        try:
            loop.call_soon_threadsafe(loop.stop)
        except RuntimeError:
            pass
        _finished.wait(2)
