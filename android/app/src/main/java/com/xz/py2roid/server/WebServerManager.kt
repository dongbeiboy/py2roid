package com.xz.py2roid.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.util.Collections

/**
 * WebSocket 服务器管理器
 *
 * 基于 NanoHTTPD WebSocket，提供：
 * - 端口 8080 的 WebSocket 服务
 * - 客户端连接/断开管理
 * - 检测结果广播
 * - HTTP 状态端点
 */
object WebServerManager {

    private const val TAG = "py2roid.WebServer"
    private const val DEFAULT_PORT = 8080

    sealed class WebSocketState {
        data object Stopped : WebSocketState()
        data object Running : WebSocketState()
        data class Error(val message: String) : WebSocketState()
    }

    private val _connectionState = MutableStateFlow<WebSocketState>(WebSocketState.Stopped)
    val connectionState: StateFlow<WebSocketState> = _connectionState.asStateFlow()

    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount.asStateFlow()

    private var server: Py2roidWebSocketServer? = null
    private val connectedClients = Collections.synchronizedList(mutableListOf<Py2roidWebSocket>())
    private var scope: CoroutineScope? = null

    /**
     * 启动 WebSocket 服务器。
     */
    fun start(port: Int = DEFAULT_PORT) {
        if (server != null) return
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("websocket-server"))

        try {
            val srv = Py2roidWebSocketServer(port)
            srv.start()
            server = srv
            _connectionState.value = WebSocketState.Running
            Log.i(TAG, "WebSocket server started on port $port")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start WebSocket server", e)
            _connectionState.value = WebSocketState.Error("启动失败: ${e.message}")
        }
    }

    /** 停止 WebSocket 服务器 */
    fun stop() {
        server?.stop()
        server = null
        synchronized(connectedClients) {
            connectedClients.forEach { it.close(NanoWSD.WebSocketFrame.CloseCode.GoingAway, "Server shutting down", false) }
            connectedClients.clear()
        }
        _clientCount.value = 0
        _connectionState.value = WebSocketState.Stopped
        scope?.cancel()
        scope = null
        Log.i(TAG, "WebSocket server stopped")
    }

    /** 广播数据到所有连接的 WebSocket 客户端 */
    fun broadcast(data: ByteArray) {
        if (_clientCount.value == 0) return
        synchronized(connectedClients) {
            val iterator = connectedClients.iterator()
            while (iterator.hasNext()) {
                val client = iterator.next()
                try {
                    client.send(data)
                } catch (e: Exception) {
                    Log.w(TAG, "Broadcast failed, removing client: ${e.message}")
                    iterator.remove()
                }
            }
        }
        _clientCount.value = connectedClients.size
    }

    fun isRunning(): Boolean = server != null

    /** WebSocket 客户端发来的消息流 */
    private val _incomingWsMessages = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 1024)
    val incomingWsMessages: Flow<ByteArray> = _incomingWsMessages.asSharedFlow()

    // ── WebSocket Server ──

    private class Py2roidWebSocketServer(val wsPort: Int) : NanoWSD(wsPort) {

        override fun openWebSocket(handshake: NanoHTTPD.IHTTPSession): NanoWSD.WebSocket {
            return Py2roidWebSocket(handshake)
        }

        override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
            val html = """
                <html><body>
                <h2>py2roid WebSocket Server</h2>
                <p>Status: Running</p>
                <p>Clients: ${WebServerManager._clientCount.value}</p>
                <p>Connect via WebSocket: <code>ws://&lt;phone-ip&gt;:$wsPort</code></p>
                </body></html>
            """.trimIndent()
            return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html", html)
        }
    }

    private class Py2roidWebSocket(handshake: NanoHTTPD.IHTTPSession)
        : NanoWSD.WebSocket(handshake) {

        override fun onOpen() {
            Log.i(TAG, "WebSocket client connected")
            synchronized(WebServerManager.connectedClients) {
                WebServerManager.connectedClients.add(this)
            }
            WebServerManager._clientCount.value = WebServerManager.connectedClients.size
        }

        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) {
            Log.i(TAG, "WebSocket client disconnected: $reason")
            synchronized(WebServerManager.connectedClients) {
                WebServerManager.connectedClients.remove(this)
            }
            WebServerManager._clientCount.value = WebServerManager.connectedClients.size
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            val payload = message.binaryPayload ?: message.textPayload?.toByteArray()
            if (payload != null) {
                Log.d(TAG, "WS message: ${payload.size}B")
                WebServerManager._incomingWsMessages.tryEmit(payload)
            }
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame) = Unit

        override fun onException(e: IOException) {
            Log.w(TAG, "WebSocket exception: ${e.message}")
            synchronized(WebServerManager.connectedClients) {
                WebServerManager.connectedClients.remove(this)
            }
            WebServerManager._clientCount.value = WebServerManager.connectedClients.size
        }
    }
}
