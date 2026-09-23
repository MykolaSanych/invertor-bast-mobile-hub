package com.chapay.homehub.otaflasher

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class OtaTarget(
    val title: String,
    val ip: String,
    val password: String,
    val assetFileName: String,
)

class MainActivity : AppCompatActivity() {
    private lateinit var wifiStatusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var progressBar: ProgressBar
    private lateinit var buttons: List<Button>
    private var isFlashing = false

    // Ті самі custom_ota_ip/custom_ota_auth, що й у platformio.ini кожного
    // пристрою.
    private val inverter = OtaTarget("Інвертор", "192.168.1.2", "npQ6bMcAzMbVayDrKTzXyzh9", "invertor_firmware.bin")
    private val loadController = OtaTarget("Контролер навантаження", "192.168.1.3", "npQ6bMcAzMbVayDrKTzXyzh9", "load_controller_firmware.bin")
    private val garage = OtaTarget("Гараж", "192.168.1.4", "npQ6bMcAzMbVayDrKTzXyzh9", "garage_firmware.bin")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wifiStatusText = findViewById(R.id.wifiStatusText)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        progressBar = findViewById(R.id.progressBar)

        val btnAll = findViewById<Button>(R.id.btnAll)
        val btnInverter = findViewById<Button>(R.id.btnInverter)
        val btnLoad = findViewById<Button>(R.id.btnLoad)
        val btnGarage = findViewById<Button>(R.id.btnGarage)
        buttons = listOf(btnAll, btnInverter, btnLoad, btnGarage)

        // "Все": спершу другорядні модулі, інвертор - останнім, щоб перезапуск
        // головного вузла не обривав заливку решти.
        btnAll.setOnClickListener { startFlash(listOf(loadController, garage, inverter)) }
        btnInverter.setOnClickListener { startFlash(listOf(inverter)) }
        btnLoad.setOnClickListener { startFlash(listOf(loadController)) }
        btnGarage.setOnClickListener { startFlash(listOf(garage)) }

        updateWifiStatus()
    }

    override fun onResume() {
        super.onResume()
        updateWifiStatus()
    }

    private fun updateWifiStatus() {
        val ip = currentWifiIp()
        wifiStatusText.text = when {
            ip == null -> "Wi-Fi не підключено. Підключись до тієї ж мережі, що й пристрої."
            ip.startsWith("192.168.1.") -> "Wi-Fi OK: телефон у мережі 192.168.1.x (адреса: $ip)"
            else -> "УВАГА: телефон у мережі $ip, а пристрої - у 192.168.1.x. Заливка, ймовірно, не спрацює."
        }
    }

    private fun currentWifiIp(): String? {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            @Suppress("DEPRECATION")
            val ipInt = wifiManager.connectionInfo?.ipAddress ?: 0
            @Suppress("DEPRECATION")
            if (ipInt == 0) null else Formatter.formatIpAddress(ipInt)
        } catch (e: Exception) {
            null
        }
    }

    private fun startFlash(targets: List<OtaTarget>) {
        if (isFlashing) return
        isFlashing = true
        setButtonsEnabled(false)
        logText.text = ""
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            val summary = mutableListOf<String>()
            for ((index, target) in targets.withIndex()) {
                progressBar.progress = 0
                appendLog("=== [${index + 1}/${targets.size}] ${target.title} (${target.ip}) ===")
                val result = OtaFlasher.flash(
                    context = applicationContext,
                    espIp = target.ip,
                    password = target.password,
                    assetFileName = target.assetFileName,
                    onProgress = { msg -> runOnUiThread { appendLog(msg) } },
                    onPercent = { pct -> runOnUiThread { progressBar.progress = pct } },
                )
                when (result) {
                    is OtaFlasher.OtaResult.Success -> {
                        appendLog("УСПІХ: ${target.title} оновлено.")
                        summary += "OK     ${target.title}"
                    }
                    is OtaFlasher.OtaResult.Failure -> {
                        appendLog("ПОМИЛКА: ${result.message}")
                        summary += "ПОМИЛКА ${target.title}"
                    }
                }
                // Пристрою потрібен час перезавантажитись, перш ніж брати наступний.
                if (index < targets.size - 1) delay(5000)
            }
            if (targets.size > 1) {
                appendLog("")
                appendLog("=== ПІДСУМОК ===")
                summary.forEach { appendLog(it) }
            }
            isFlashing = false
            setButtonsEnabled(true)
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        buttons.forEach { it.isEnabled = enabled }
    }

    private fun appendLog(line: String) {
        logText.append(if (logText.text.isEmpty()) line else "\n$line")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
