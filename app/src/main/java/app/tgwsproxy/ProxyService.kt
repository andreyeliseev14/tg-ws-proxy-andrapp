package app.tgwsproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import java.util.concurrent.Executors

/**
 * Foreground-служба: держит Python-прокси живым, перезапускает его при сбое
 * и при смене сети (Wi-Fi <-> мобильный интернет).
 */
class ProxyService : Service() {

    companion object {
        const val ACTION_STOP = "app.tgwsproxy.STOP"
        private const val CHANNEL_ID = "proxy"
        private const val NOTIF_ID = 1
        private const val TAG = "TgWsProxy"

        @Volatile
        var running = false
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ProxyService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ProxyService::class.java).setAction(ACTION_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val control = Executors.newSingleThreadExecutor()
    private var worker: Thread? = null
    private var userStopped = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val restartRunnable = Runnable { restartProxy() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setEnabled(this, false)
            shutdown()
            return START_NOT_STICKY
        }

        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        Prefs.setEnabled(this, true)
        userStopped = false
        running = true
        control.execute { startProxy() }
        registerNetworkWatcher()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterNetworkWatcher()
        running = false
        super.onDestroy()
    }

    // ---------- управление Python-прокси ----------

    private fun startProxy() {
        if (worker?.isAlive == true) return
        val secret = Prefs.secret(this)
        val logPath = Prefs.logFile(this).absolutePath
        worker = Thread({
            try {
                val entry = Python.getInstance().getModule("android_entry")
                entry.callAttr("run", Prefs.HOST, Prefs.PORT, secret, Prefs.DC_IPS, logPath, 4)
            } catch (e: Throwable) {
                Log.e(TAG, "proxy stopped with error", e)
            }
            // Если нас никто не просил остановиться - поднимаем заново.
            if (!userStopped) {
                main.postDelayed({ control.execute { startProxy() } }, 3000)
            }
        }, "tg-ws-proxy").also { it.start() }
    }

    private fun stopProxy() {
        try {
            Python.getInstance().getModule("android_entry").callAttr("stop")
        } catch (e: Throwable) {
            Log.e(TAG, "stop failed", e)
        }
        worker?.join(3000)
        worker = null
    }

    private fun restartProxy() {
        control.execute {
            if (userStopped) return@execute
            Log.i(TAG, "restarting proxy after network change")
            // userStopped временно true, чтобы поток не перезапустил себя сам.
            userStopped = true
            stopProxy()
            userStopped = false
            startProxy()
        }
    }

    private fun shutdown() {
        userStopped = true
        main.removeCallbacksAndMessages(null)
        unregisterNetworkWatcher()
        control.execute {
            stopProxy()
            running = false
            main.post {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // ---------- реакция на смену сети ----------

    private fun registerNetworkWatcher() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            @Volatile private var lost = false
            override fun onLost(network: Network) { lost = true }
            override fun onAvailable(network: Network) {
                if (lost) {
                    lost = false
                    // небольшая задержка, чтобы сеть успела стабилизироваться
                    main.removeCallbacks(restartRunnable)
                    main.postDelayed(restartRunnable, 2000)
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Throwable) {
            Log.e(TAG, "network callback failed", e)
        }
    }

    private fun unregisterNetworkWatcher() {
        val cb = networkCallback ?: return
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (_: Throwable) {}
        networkCallback = null
    }

    // ---------- уведомление ----------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Прокси", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("TG WS Proxy работает")
            .setContentText("${Prefs.HOST}:${Prefs.PORT}")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Остановить", stop)
            .build()
    }
}
