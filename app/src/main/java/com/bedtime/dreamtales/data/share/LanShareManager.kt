package com.bedtime.dreamtales.data.share

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.bedtime.dreamtales.core.share.ShareBundleProtocol
import com.bedtime.dreamtales.data.repository.ShareBundleRepository
import com.bedtime.dreamtales.data.repository.ShareImportResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

data class LanShareDevice(
    val name: String,
    val host: String,
    val port: Int
) {
    val displayName: String get() = ShareBundleProtocol.deviceLabel(name)
}

data class LanShareServerInfo(
    val deviceName: String,
    val port: Int,
    val pairCode: String = ""
)

/**
 * 局域网分享发送端：内置极简 HTTP 文件服务 + NSD/mDNS 服务广播。
 * 仅在同一 Wi-Fi 内提供 manifest.json / 故事 / 音色样本的 ZIP 包。
 */
class LanShareServer(private val context: Context) {

    private val repository = ShareBundleRepository(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile
    private var running = false

    @Volatile
    private var registeredName: String = defaultDeviceName()

    /** 本次分享会话的 4 位配对码，接收端必须提供才能拉取分享包。 */
    @Volatile
    private var pairCode: String = ""

    fun start(): LanShareServerInfo {
        if (running) {
            return LanShareServerInfo(registeredName, serverSocket?.localPort ?: 0, pairCode)
        }
        pairCode = ShareBundleProtocol.generatePairCode()
        val socket = ServerSocket(0)
        serverSocket = socket
        running = true
        acquireMulticastLock()
        startNsd(socket.localPort)
        acceptJob = scope.launch {
            while (isActive && running) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                launch { handleClient(client) }
            }
        }
        return LanShareServerInfo(registeredName, socket.localPort, pairCode)
    }

    fun stop() {
        running = false
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        serverSocket = null
        try {
            val listener = registrationListener
            if (listener != null) nsdManager?.unregisterService(listener)
        } catch (e: Exception) {
            // ignore
        }
        registrationListener = null
        nsdManager = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("DreamTalesLanShare").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            // 部分设备不支持多播锁，不影响 NSD 尝试
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (e: Exception) {
            // ignore
        }
        multicastLock = null
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun startNsd(port: Int) {
        try {
            val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsdManager = manager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = defaultDeviceName()
                serviceType = ShareBundleProtocol.SERVICE_TYPE
                setPort(port)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    registeredName = info.serviceName.ifBlank { defaultDeviceName() }
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    // 广播失败不影响直连 IP 下载
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            }
            registrationListener = listener
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            // 某些定制 ROM 可能禁用 NSD，仍保留手动 HTTP 服务能力
        }
    }

    private suspend fun handleClient(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 15_000
                val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val requestLine = input.readLine().orEmpty()
                val rawTarget = requestLine.split(' ').getOrNull(1).orEmpty()
                // 消费请求头
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                }

                val path = rawTarget.substringBefore('?')
                val query = rawTarget.substringAfter('?', "")
                val providedCode = query.split('&')
                    .mapNotNull { part ->
                        val pieces = part.split('=', limit = 2)
                        if (pieces.size == 2 && pieces[0] == ShareBundleProtocol.CODE_QUERY) {
                            pieces[1]
                        } else null
                    }
                    .firstOrNull()

                val output = BufferedOutputStream(socket.getOutputStream())
                when {
                    path.startsWith(ShareBundleProtocol.PING_PATH) -> {
                        val body = """{"app":"DreamTales","device":"$registeredName"}"""
                            .toByteArray(Charsets.UTF_8)
                        writeHeaders(output, 200, ShareBundleProtocol.MIME_JSON, body.size)
                        output.write(body)
                    }
                    path.startsWith(ShareBundleProtocol.BUNDLE_PATH) -> {
                        // 必须携带正确配对码，避免同 Wi-Fi 下任意设备静默拉走家长音色样本
                        if (pairCode.isBlank() || providedCode != pairCode) {
                            val body = "配对码不正确".toByteArray(Charsets.UTF_8)
                            writeHeaders(output, 403, "text/plain; charset=utf-8", body.size)
                            output.write(body)
                        } else {
                            val bundle = repository.buildBundle(registeredName)
                            val bytes = bundle.readBytes()
                            writeHeaders(output, 200, ShareBundleProtocol.MIME_ZIP, bytes.size)
                            output.write(bytes)
                            bundle.delete()
                        }
                    }
                    else -> {
                        val body = "Not Found".toByteArray(Charsets.UTF_8)
                        writeHeaders(output, 404, "text/plain; charset=utf-8", body.size)
                        output.write(body)
                    }
                }
                output.flush()
            }
        } catch (e: Exception) {
            // 单个请求失败不影响服务
        }
    }

    private fun writeHeaders(
        output: BufferedOutputStream,
        code: Int,
        contentType: String,
        contentLength: Int
    ) {
        val statusText = if (code == 200) "OK" else "Error"
        val headers = buildString {
            append("HTTP/1.1 $code $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: $contentLength\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.UTF_8))
    }

    private fun defaultDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.trim().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.trim()
        return "月眠-$manufacturer $model".take(40)
    }
}

/**
 * 局域网分享接收端：通过 NSD 发现同 Wi-Fi 下正在分享的家庭设备。
 */
@Suppress("DEPRECATION")
class LanShareDiscovery(private val context: Context) {

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val resolvedNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun start(
        onDeviceFound: (LanShareDevice) -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        resolvedNames.clear()
        try {
            acquireMulticastLock()
            val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsdManager = manager
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType != ShareBundleProtocol.SERVICE_TYPE) return
                    try {
                        manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val host = info.host?.hostAddress ?: return
                                if (info.port <= 0) return
                                val name = info.serviceName.ifBlank { "月眠" }
                                if (resolvedNames.add("$name|$host|${info.port}")) {
                                    onDeviceFound(LanShareDevice(name, host, info.port))
                                }
                            }
                        })
                    } catch (e: Exception) {
                        onError("发现设备失败：${e.message}")
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    onError("无法开启局域网扫描（错误码 $errorCode）")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            discoveryListener = listener
            manager.discoverServices(ShareBundleProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            onError("当前设备不支持局域网发现：${e.message}")
        }
    }

    fun stop() {
        try {
            val listener = discoveryListener
            if (listener != null) nsdManager?.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            // ignore
        }
        discoveryListener = null
        nsdManager = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("DreamTalesLanDiscovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (e: Exception) {
            // ignore
        }
        multicastLock = null
    }
}

/**
 * 家庭局域网互传统一入口。
 */
class LanShareManager(context: Context) {

    private val appContext = context.applicationContext
    private val repository = ShareBundleRepository(appContext)
    private val server = LanShareServer(appContext)
    private val discovery = LanShareDiscovery(appContext)

    fun startServing(): LanShareServerInfo = server.start()

    fun stopServing() = server.stop()

    fun startDiscovery(
        onDeviceFound: (LanShareDevice) -> Unit,
        onError: (String) -> Unit
    ) = discovery.start(onDeviceFound, onError)

    fun stopDiscovery() = discovery.stop()

    suspend fun downloadAndImport(
        device: LanShareDevice,
        pairCode: String,
        onProgress: (Int) -> Unit = {}
    ): ShareImportResult = withContext(Dispatchers.IO) {
        val url = URL(ShareBundleProtocol.downloadUrl(device.host, device.port, pairCode))
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 60_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", ShareBundleProtocol.MIME_ZIP)

        try {
            if (connection.responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                throw RuntimeException("配对码不正确，请核对对方设备上显示的 4 位数字")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw RuntimeException("对方设备返回 HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            val temp = File(appContext.cacheDir, "received_share_${System.currentTimeMillis()}.zip")
            connection.inputStream.use { input ->
                FileOutputStream(temp).buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress(((downloaded * 90) / total).toInt().coerceIn(0, 90))
                        }
                    }
                }
            }
            onProgress(92)
            try {
                repository.importBundle(temp)
            } finally {
                temp.delete()
            }
        } finally {
            connection.disconnect()
        }
    }

    fun release() {
        server.release()
        discovery.stop()
    }
}
