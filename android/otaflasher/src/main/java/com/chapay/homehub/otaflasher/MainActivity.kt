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
    private lateinit var btnGarage: Button
    private lateinit var btnInverter: Button
    private var isFlashing = false

    // Ті самі custom_ota_ip/custom_ota_auth, що й у platformio.ini кожного
    // пристрою.
    private val garage = OtaTarget(
        title = "Гараж",
        ip = "192.168.1.4",
        password = "npQ6bMcAzMbVayDrKTzXyzh9",
        assetFileName = "garage_firmware.bin",
    )
    private val inverter = OtaTarget(
        title = "Інвертор",
        ip = "192.168.1.2",
        password = "npQ6bMcAzMbVayDrKTzXyzh9",
        assetFileName = "invertor_firmware.bin",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wifiStatusText = findViewById(R.id.wifiStatusText)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        progressBar = findViewById(R.id.progressBar)
        btnGarage = findViewById(R.id.btnGarage)
        btnInverter = findViewById(R.id.btnInverter)

        btnGarage.setOnClickListener { startFlash(garage) }
        btnInverter.setOnClickListener { startFlash(inverter) }

        updateWifiStatus()
    }

    override fun onResume() {
        super.onResume()
        updateWifiStatus()
    }

    private fun updateWifiStatus() {
        val ip = currentWifiIp()
        wifiStatusText.text = if (ip == null) {
            "Wi-Fi не підключено. Підключись до тієї ж мережі, що й пристрої."
        } else if (ip.startsWith("192.168.1.")) {
            "Wi-Fi OK: телефон у мережі 192.168.1.x (адреса: $ip)"
        } else {
            "УВАГА: телефон у мережі $ip, а пристрої - у 192.168.1.x. Заливка, ймовірно, не спрацює."
        }
    }

    private fun currentWifiIp(): String? {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            @Suppress("DEPRECATION")
            val ipInt = wifiManager.connectionInfo?.ipAddress ?: 0
            if (ipInt == 0) null else Formatter.formatIpAddress(ipInt)
        } catch (e: Exception) {
            null
        }
    }

    private fun startFlash(target: OtaTarget) {
        if (isFlashing) return
        isFlashing = true
        setButtonsEnabled(false)
        logText.text = ""
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0

        appendLog("=== Заливка: ${target.title} (${target.ip}) ===")

        lifecycleScope.launch {
            val result = OtaFlasher.flash(
                context = applicationContext,
                espIp = target.ip,
                password = target.password,
                assetFileName = target.assetFileName,
                onProgress = { msg -> runOnUiThread { appendLog(msg) } },
                onPercent = { pct -> runOnUiThread { progressBar.progress = pct } },
            )
            when (result) {
                is OtaFlasher.OtaResult.Success -> appendLog("УСПІХ: ${target.title} оновлено.")
                is OtaFlasher.OtaResult.Failure -> appendLog("ПОМИЛКА: ${result.message}")
            }
            isFlashing = false
            setButtonsEnabled(true)
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnGarage.isEnabled = enabled
        btnInverter.isEnabled = enabled
    }

    private fun appendLog(line: String) {
        logText.append(if (logText.text.isEmpty()) line else "\n$line")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
