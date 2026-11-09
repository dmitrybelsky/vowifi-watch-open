package dev.belsky.vowifiwatch

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.FileKeyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Выполнение shell-команд через локальный ADB (беспроводная отладка, uid 2000),
 * встроенный в приложение (библиотека adb-kt, без Shizuku и сторонних приложений).
 *
 * Одноразовое сопряжение: [pair] по коду из «Настройки → Для разработчиков →
 * Беспроводная отладка → Сопряжение устройства с кодом». Далее приложение само
 * подключается к 127.0.0.1; порт подключения меняется после перезагрузки и
 * находится через mDNS-сервис `_adb-tls-connect._tcp` (NsdManager).
 */
class AdbShellExecutor(private val context: Context) {

    /** Результат выполнения: error != null — ошибка транспорта/подключения (команда не выполнялась). */
    data class Result(
        val exitCode: Int = -1,
        val stdout: String = "",
        val stderr: String = "",
        val error: String? = null,
    ) {
        val isSuccess: Boolean get() = error == null && exitCode == 0

        /** Краткое описание исхода для журнала. */
        fun describe(): String = when {
            error != null -> "ошибка подключения: $error"
            exitCode == 0 -> "OK"
            else -> "exit=$exitCode" + (stderr.trim().takeIf { it.isNotEmpty() }?.let { ": $it" } ?: "")
        }
    }

    private val prefs = Prefs(context)
    private val keyFile: File get() = File(context.filesDir, ".android/adbkey")

    private fun keyProvider(): FileKeyProvider {
        keyFile.parentFile?.mkdirs()
        return FileKeyProvider(keyFile)
    }

    /** Сопряжение выполнено (RSA-ключ существует). */
    fun isPaired(): Boolean = keyFile.exists() && keyFile.length() > 0

    /**
     * Проверка доступности канала: ключ есть, TLS-подключение успешно и
     * `id` подтверждает uid 2000 (shell).
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (!isPaired()) return@withContext false
        val client = connect() ?: return@withContext false
        try {
            val r = withTimeoutOrNull(EXEC_TIMEOUT_MS) { client.shell("id") }
            r != null && r.stdoutText.contains("uid=2000")
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { client.close() }
        }
    }

    /**
     * Выполнить shell-команду через локальный ADB.
     * Никогда не бросает исключения: любая ошибка → Result(error=...).
     */
    suspend fun exec(cmd: String): Result = withContext(Dispatchers.IO) {
        if (!isPaired()) {
            return@withContext Result(error = "сопряжение не выполнено (нет ключа adbkey)")
        }
        val client = connect() ?: return@withContext Result(error = lastConnectError ?: "не удалось подключиться")
        try {
            val r = withTimeout(EXEC_TIMEOUT_MS) { client.shell(cmd) }
            Result(exitCode = r.exitCode, stdout = r.stdoutText, stderr = r.stderrText)
        } catch (t: Throwable) {
            Result(error = "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    /**
     * Одноразовое сопряжение по 6-значному коду из «Беспроводной отладки».
     * После успеха сразу ищет connect-порт через NSD и сохраняет его.
     */
    suspend fun pair(host: String, port: Int, code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val r = withTimeout(CONNECT_TIMEOUT_MS + 5_000) {
                AdbClient.pairTls(host, port, code.trim(), keyProvider())
            }
            if (r.isSuccess) {
                // Порт не сохраняем вслепую: connect() сам найдёт и проверит его через NSD
                true
            } else {
                EventLog.add("ADB-сопряжение: отказ (${r.exceptionOrNull()?.javaClass?.simpleName}: ${r.exceptionOrNull()?.message})")
                false
            }
        } catch (t: Throwable) {
            EventLog.add("ADB-сопряжение: ошибка (${t.javaClass.simpleName}: ${t.message})")
            false
        }
    }

    // --- Подключение ---

    @Volatile
    private var lastConnectError: String? = null

    /** Подключиться к 127.0.0.1: сохранённый порт, затем все порты из NSD-окна. */
    private suspend fun connect(): AdbClient? {
        val saved = prefs.adbPort
        val discovered = discoverConnectPorts()
        // NSD может отдавать устаревший («призрачный») порт прошлой активации adbd —
        // проверяем каждый кандидат реальным подключением, сохраняем только успешный.
        val candidates = (listOf(saved).filter { it > 0 } + discovered).distinct()
        if (candidates.isEmpty()) {
            lastConnectError = "порт беспроводной отладки не найден (NSD `_adb-tls-connect._tcp` молчит; включена ли беспроводная отладка?)"
            return null
        }
        for (port in candidates) {
            val client = connectTo(port)
            if (client != null) {
                if (port != saved) {
                    prefs.adbPort = port
                    EventLog.add("ADB: порт подключения найден: $port")
                }
                return client
            }
        }
        return null
    }

    private suspend fun connectTo(port: Int): AdbClient? {
        return try {
            val client = withTimeout(CONNECT_TIMEOUT_MS) {
                AdbClient.connectTls(HOST, port, keyProvider(), handshakeTimeoutMs = CONNECT_TIMEOUT_MS)
            }
            lastConnectError = null
            client
        } catch (t: Throwable) {
            lastConnectError = "порт $port: ${t.javaClass.simpleName}: ${t.message}"
            null
        }
    }

    // --- NSD: поиск портов `_adb-tls-connect._tcp` ---

    /**
     * Собирает ВСЕ порты, увиденные за окно NSD_TIMEOUT_MS (resolve каждого найденного
     * сервиса). Несколько записей — норма: устаревшая («призрачная») от прошлой
     * активации adbd + свежая. Отличаем их только реальным подключением (см. connect).
     */
    @Suppress("DEPRECATION")
    private suspend fun discoverConnectPorts(): List<Int> = withContext(Dispatchers.IO) {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return@withContext emptyList()
        val ports = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        var discoveryListener: NsdManager.DiscoveryListener? = null
        try {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}

                override fun onServiceFound(service: NsdServiceInfo) {
                    try {
                        nsd.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}

                            override fun onServiceResolved(si: NsdServiceInfo) {
                                if (si.port > 0) ports.add(si.port)
                            }
                        })
                    } catch (_: Throwable) {
                    }
                }

                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            discoveryListener = listener
            try {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (_: Throwable) {
                return@withContext emptyList()
            }
            // Ждём всё окно целиком: свежая запись adbd может появиться
            // через 1-2 секунды после «призрачной»
            kotlinx.coroutines.delay(NSD_TIMEOUT_MS)
        } finally {
            discoveryListener?.let {
                try {
                    nsd.stopServiceDiscovery(it)
                } catch (_: Throwable) {
                }
            }
        }
        ports.distinct()
    }

    companion object {
        private const val HOST = "127.0.0.1"
        private const val SERVICE_TYPE = "_adb-tls-connect._tcp"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val EXEC_TIMEOUT_MS = 30_000L
        private const val NSD_TIMEOUT_MS = 5_000L
    }
}
