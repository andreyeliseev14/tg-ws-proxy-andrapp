package app.tgwsproxy

import android.content.Context
import java.io.File
import java.security.SecureRandom

object Prefs {
    const val HOST = "127.0.0.1"
    const val PORT = 1443
    const val DC_IPS = "2:149.154.167.220,4:149.154.167.220"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    /** Секрет генерируется один раз и хранится на телефоне. */
    fun secret(ctx: Context): String {
        val s = sp(ctx)
        s.getString("secret", null)?.let { return it }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hex = bytes.joinToString("") { "%02x".format(it) }
        s.edit().putString("secret", hex).apply()
        return hex
    }

    fun link(ctx: Context): String =
        "tg://proxy?server=$HOST&port=$PORT&secret=dd${secret(ctx)}"

    /** Пользователь включил прокси (нужно для автозапуска после перезагрузки). */
    fun isEnabled(ctx: Context) = sp(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("enabled", v).apply()

    fun isAutostart(ctx: Context) = sp(ctx).getBoolean("autostart", true)
    fun setAutostart(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("autostart", v).apply()

    fun logFile(ctx: Context): File = File(ctx.filesDir, "proxy.log")
}
