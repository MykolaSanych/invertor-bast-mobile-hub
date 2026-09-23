package com.chapay.homehub.otaflasher

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest

/**
 * Нативна реалізація протоколу ArduinoOTA (espota.py) на Kotlin/сокетах -
 * без Python. Точно відтворює формат UDP-запрошення, MD5-автентифікацію
 * (виклик-відповідь) і TCP-передачу файлу чанками з підтвердженням, як у
 * https://github.com/espressif/arduino-esp32/blob/master/tools/espota.py.
 *
 * Головна причина існування цього застосунку: espota.py надсилає запрошення
 * по UDP, і ESP32 підключається НАЗАД по TCP на IP, з якого прийшов пакет.
 * Якщо ПК підключений через VPN/ZeroTier, пристрій намагається достукатись
 * до VPN-адреси ПК, якої з його локальної мережі не видно - заливка виснна
 * не проходить. Якщо ж цей застосунок запущено з телефону, що фізично в
 * тій самій Wi-Fi мережі, що й пристрій, - зворотна адреса є реальною
 * локальною адресою телефону, і TCP-з'єднання встановлюється без проблем.
 */
object OtaFlasher {
    private const val CMD_FLASH = 0
    private const val CMD_AUTH = 200
    private const val ESP_OTA_PORT = 3232

    private const val INVITE_TIMEOUT_MS = 3000
    private const val INVITE_MAX_TRIES = 10
    private const val AUTH_TIMEOUT_MS = 10000
    private const val ACCEPT_TIMEOUT_MS = 15000
    private const val CHUNK_SIZE = 1024
    private const val CHUNK_ACK_TIMEOUT_MS = 15000
    private const val FINAL_RESULT_TIMEOUT_MS = 60000
    private const val FINAL_RESULT_MAX_TRIES = 5

    sealed class OtaResult {
        data object Success : OtaResult()
        data class Failure(val message: String) : OtaResult()
    }

    suspend fun flash(
        context: Context,
        espIp: String,
        password: String,
        assetFileName: String,
        onProgress: (String) -> Unit,
        onPercent: (Int) -> Unit,
    ): OtaResult = withContext(Dispatchers.IO) {
        var serverSocket: ServerSocket? = null
        var socket: Socket? = null
        try {
            val firmwareBytes = context.assets.open(assetFileName).use { it.readBytes() }
            val contentSize = firmwareBytes.size
            val fileMd5 = md5Hex(firmwareBytes)
            onProgress("Прошивка: $assetFileName (${contentSize / 1024} КБ, md5=$fileMd5)")

            serverSocket = ServerSocket(0)
            val localPort = serverSocket.localPort
            onProgress("Слухаю TCP на порту $localPort")

            val inviteMessage = "$CMD_FLASH $localPort $contentSize $fileMd5\n"

            onProgress("Надсилаю запрошення на $espIp:$ESP_OTA_PORT...")
            var inviteResponse: String? = null
            var tries = 0
            while (tries < INVITE_MAX_TRIES && inviteResponse == null) {
                tries++
                inviteResponse = sendUdpAndReceive(espIp, ESP_OTA_PORT, inviteMessage, INVITE_TIMEOUT_MS)
                if (inviteResponse == null) onProgress("...спроба $tries/$INVITE_MAX_TRIES без відповіді")
            }
            if (inviteResponse == null) {
                return@withContext OtaResult.Failure(
                    "Пристрій не відповідає на запрошення. Перевір IP-адресу та що телефон у мережі 192.168.1.x.",
                )
            }

            if (inviteResponse != "OK") {
                if (inviteResponse.startsWith("AUTH")) {
                    onProgress("Потрібна автентифікація...")
                    val nonce = inviteResponse.trim().split(" ").getOrNull(1)
                        ?: return@withContext OtaResult.Failure("Некоректна відповідь AUTH: $inviteResponse")

                    val cnonceText = "$assetFileName$contentSize$fileMd5$espIp"
                    val cnonce = md5Hex(cnonceText.toByteArray(Charsets.UTF_8))
                    val passMd5 = md5Hex(password.toByteArray(Charsets.UTF_8))
                    val resultHash = md5Hex("$passMd5:$nonce:$cnonce".toByteArray(Charsets.UTF_8))
                    val authMessage = "$CMD_AUTH $cnonce $resultHash\n"

                    val authResponse = sendUdpAndReceive(espIp, ESP_OTA_PORT, authMessage, AUTH_TIMEOUT_MS)
                    if (authResponse != "OK") {
                        return@withContext OtaResult.Failure(
                            "Автентифікація не пройшла (невірний пароль?): ${authResponse ?: "немає відповіді"}",
                        )
                    }
                    onProgress("Автентифіковано.")
                } else {
                    return@withContext OtaResult.Failure("Неочікувана відповідь від пристрою: $inviteResponse")
                }
            }

            onProgress("Очікую з'єднання від пристрою...")
            serverSocket.soTimeout = ACCEPT_TIMEOUT_MS
            val acceptedSocket = try {
                serverSocket.accept()
            } catch (e: SocketTimeoutException) {
                return@withContext OtaResult.Failure("Пристрій не підключився для передачі файлу (тайм-аут).")
            }
            socket = acceptedSocket
            serverSocket.close()
            serverSocket = null

            acceptedSocket.soTimeout = CHUNK_ACK_TIMEOUT_MS
            val out = acceptedSocket.getOutputStream()
            val input = acceptedSocket.getInputStream()
            val ackBuf = ByteArray(64)

            var offset = 0
            var lastResponseHadOk = false
            var lastPercent = -1
            onProgress("Заливаю прошивку...")
            while (offset < contentSize) {
                val end = minOf(offset + CHUNK_SIZE, contentSize)
                out.write(firmwareBytes, offset, end - offset)
                out.flush()
                offset = end

                val n = input.read(ackBuf)
                lastResponseHadOk = n > 0 && String(ackBuf, 0, n, Charsets.UTF_8).contains("OK")

                val percent = offset * 100 / contentSize
                if (percent != lastPercent) {
                    lastPercent = percent
                    onPercent(percent)
                }
            }

            if (lastResponseHadOk) {
                onProgress("Готово! Пристрій перезавантажується з новою прошивкою.")
                return@withContext OtaResult.Success
            }

            onProgress("Очікую підтвердження від пристрою...")
            acceptedSocket.soTimeout = FINAL_RESULT_TIMEOUT_MS
            repeat(FINAL_RESULT_MAX_TRIES) {
                val n = input.read(ackBuf)
                if (n > 0) {
                    val data = String(ackBuf, 0, n, Charsets.UTF_8)
                    onProgress("Відповідь пристрою: $data")
                    if (data.contains("OK")) {
                        return@withContext OtaResult.Success
                    }
                }
            }
            OtaResult.Failure("Пристрій не підтвердив успішне завершення заливки.")
        } catch (e: Exception) {
            OtaResult.Failure("Помилка: ${e.message ?: e.toString()}")
        } finally {
            runCatching { socket?.close() }
            runCatching { serverSocket?.close() }
        }
    }

    private fun sendUdpAndReceive(host: String, port: Int, message: String, timeoutMs: Int): String? {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val address = InetAddress.getByName(host)
            val sendData = message.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(sendData, sendData.size, address, port))
            val buf = ByteArray(64)
            val packet = DatagramPacket(buf, buf.size)
            return try {
                socket.receive(packet)
                String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
            } catch (e: SocketTimeoutException) {
                null
            }
        }
    }

    private fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
