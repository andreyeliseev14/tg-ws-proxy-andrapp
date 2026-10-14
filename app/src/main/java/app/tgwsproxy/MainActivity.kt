package app.tgwsproxy

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var powerSwitch: Switch
    private lateinit var batteryBtn: Button
    private val ui = Handler(Looper.getMainLooper())
    private var suppressSwitch = false

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        fun text(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
            text = s; textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        fun button(s: String, onClick: () -> Unit) = Button(this).apply {
            text = s; isAllCaps = false; setOnClickListener { onClick() }
        }

        col.addView(text("TG WS Proxy", 26f, true))
        col.addView(text("Локальный MTProto-прокси для Telegram (порт ${Prefs.PORT})", 14f).apply {
            setPadding(0, dp(4), 0, dp(16))
        })

        status = text("", 16f).apply { setPadding(0, 0, 0, dp(8)) }
        col.addView(status)

        powerSwitch = Switch(this).apply {
            text = "Прокси включён"
            textSize = 18f
            setOnCheckedChangeListener { _, checked ->
                if (suppressSwitch) return@setOnCheckedChangeListener
                if (checked) ProxyService.start(this@MainActivity)
                else ProxyService.stop(this@MainActivity)
            }
        }
        col.addView(powerSwitch)

        col.addView(button("1. Подключить Telegram (открыть ссылку)") { openInTelegram() })
        col.addView(button("Скопировать ссылку прокси") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("proxy", Prefs.link(this)))
            Toast.makeText(this, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
        })

        batteryBtn = button("2. Разрешить работу в фоне (батарея)") { requestIgnoreBattery() }
        col.addView(batteryBtn)

        val auto = Switch(this).apply {
            text = "Запускать после перезагрузки телефона"
            textSize = 15f
            isChecked = Prefs.isAutostart(this@MainActivity)
            setOnCheckedChangeListener { _, c -> Prefs.setAutostart(this@MainActivity, c) }
            setPadding(0, dp(12), 0, dp(12))
        }
        col.addView(auto)

        col.addView(button("Показать лог") { showLog() })

        col.addView(text(
            "Если на Xiaomi/Huawei/Honor/OPPO/Realme связь всё равно обрывается — " +
                    "включите для приложения «Автозапуск» и закрепите его в списке недавних.",
            12f
        ).apply { setPadding(0, dp(12), 0, 0); gravity = Gravity.START })

        return ScrollView(this).apply { addView(col) }
    }

    private fun refresh() {
        val on = ProxyService.running
        status.text = if (on) "● Работает — ${Prefs.HOST}:${Prefs.PORT}" else "○ Остановлен"
        suppressSwitch = true
        powerSwitch.isChecked = on
        suppressSwitch = false

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ok = pm.isIgnoringBatteryOptimizations(packageName)
        batteryBtn.text = if (ok) "2. Работа в фоне разрешена ✓"
        else "2. Разрешить работу в фоне (батарея)"
    }

    private fun openInTelegram() {
        if (!ProxyService.running) ProxyService.start(this)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Prefs.link(this))))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Telegram не найден", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestIgnoreBattery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Уже разрешено", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun showLog() {
        val f = Prefs.logFile(this)
        val txt = if (f.exists()) f.readLines().takeLast(80).joinToString("\n") else "Лог пока пуст"
        val tv = TextView(this).apply {
            text = txt; textSize = 11f; setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Лог прокси")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Закрыть", null)
            .show()
    }
}
