package com.example.data.feed.live

import android.system.Os
import android.system.OsConstants
import com.example.domain.model.PriceTick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * SocketFactory enabling SO_KEEPALIVE and ~30s TCP keepalive idle where supported.
 */
class KeepAliveSocketFactory(
    private val delegate: SocketFactory = SocketFactory.getDefault(),
    private val keepAliveIdleSeconds: Int = 30
) : SocketFactory() {

    private fun configure(socket: Socket): Socket {
        runCatching {
            socket.keepAlive = true
            socket.tcpNoDelay = true
            // Best-effort OS TCP_KEEPIDLE (~30s) via ParcelFileDescriptor / FileDescriptor if exposed
            val implField = Socket::class.java.getDeclaredField("impl").apply { isAccessible = true }
            val impl = implField.get(socket)
            if (impl != null) {
                val fdMethod = impl.javaClass.getDeclaredMethod("getFileDescriptor").apply { isAccessible = true }
                val fd = fdMethod.invoke(impl) as? FileDescriptor
                if (fd != null) {
                    val tcpKeepIdle = 4 // Linux TCP_KEEPIDLE
                    Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, tcpKeepIdle, keepAliveIdleSeconds)
                }
            }
        }
        return socket
    }

    override fun createSocket(): Socket = configure(delegate.createSocket())
    override fun createSocket(host: String?, port: Int): Socket = configure(delegate.createSocket(host, port))
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        configure(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress?, port: Int): Socket = configure(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        configure(delegate.createSocket(address, port, localAddress, localPort))
}

fun createLiveWebSocketOkHttpClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .socketFactory(KeepAliveSocketFactory())
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
}

sealed interface WebSocketEvent {
    data class Open(val webSocket: WebSocket) : WebSocketEvent
    data class TextMessage(val text: String) : WebSocketEvent
    data class BinaryMessage(val bytes: ByteString) : WebSocketEvent
    data class Closed(val code: Int, val reason: String) : WebSocketEvent
    data class Failure(
        val throwable: Throwable,
        val responseCode: Int?,
        val errorHeader: String? = null
    ) : WebSocketEvent
}

/**
 * CallbackFlow bridge for OkHttp WebSocket events using channel.trySend on OkHttp's thread.
 */
fun OkHttpClient.webSocketEventsFlow(
    requestProvider: suspend () -> Request,
    appPingIntervalMs: Long? = null,
    appPingPayload: String? = null
): Flow<WebSocketEvent> = callbackFlow {
    val request = try {
        requestProvider()
    } catch (t: Throwable) {
        trySend(WebSocketEvent.Failure(t, null))
        close()
        return@callbackFlow
    }

    var activeSocket: WebSocket? = null
    val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            trySend(WebSocketEvent.Open(webSocket))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            trySend(WebSocketEvent.TextMessage(text))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            trySend(WebSocketEvent.BinaryMessage(bytes))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            trySend(WebSocketEvent.Closed(code, reason))
            close()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val headerErr = response?.header("x-error-message")
                ?.takeIf { it.isNotBlank() }
                ?: response?.header("X-Error-Message")?.takeIf { it.isNotBlank() }
                ?: runCatching { response?.body?.string()?.trim()?.take(160)?.takeIf { it.isNotBlank() } }.getOrNull()
            trySend(WebSocketEvent.Failure(t, response?.code, headerErr))
            close()
        }
    }

    activeSocket = newWebSocket(request, listener)

    val pingJob = if (appPingIntervalMs != null && appPingPayload != null) {
        launch(Dispatchers.IO) {
            while (isActive) {
                delay(appPingIntervalMs)
                activeSocket?.send(appPingPayload)
            }
        }
    } else null

    awaitClose {
        pingJob?.cancel()
        activeSocket?.close(1000, "Flow closed")
    }
}

/**
 * Reconnecting WebSocket wrapper with exponential backoff & jitter.
 */
fun reconnectingWebSocketFlow(
    client: OkHttpClient,
    backoff: BackoffStrategy = BackoffStrategy(),
    requestProvider: suspend () -> Request,
    appPingIntervalMs: Long? = null,
    appPingPayload: String? = null,
    onFailure: ((WebSocketEvent.Failure) -> Unit)? = null,
    onStatusChange: (LiveConnectionStatus, String) -> Unit
): Flow<WebSocketEvent> = flow {
    var attempt = 0
    var lastFailureReason: String? = null
    while (true) {
        onStatusChange(
            if (attempt == 0) LiveConnectionStatus.CONNECTING else LiveConnectionStatus.RECONNECTING,
            if (attempt == 0) {
                "Connecting WebSocket..."
            } else {
                val prev = lastFailureReason
                if (!prev.isNullOrBlank()) "Reconnecting (#${attempt + 1}) • $prev"
                else "Reconnecting (attempt ${attempt + 1})..."
            }
        )
        var connectedInAttempt = false
        var failed = false
        var failureReason = "Connection closed"

        client.webSocketEventsFlow(requestProvider, appPingIntervalMs, appPingPayload).collect { event ->
            when (event) {
                is WebSocketEvent.Open -> {
                    connectedInAttempt = true
                    attempt = 0
                    lastFailureReason = null
                    onStatusChange(LiveConnectionStatus.CONNECTED, "Live WebSocket connected")
                    emit(event)
                }
                is WebSocketEvent.TextMessage, is WebSocketEvent.BinaryMessage -> {
                    emit(event)
                }
                is WebSocketEvent.Closed -> {
                    failed = true
                    failureReason = event.reason.ifBlank { "Closed (${event.code})" }
                    lastFailureReason = failureReason
                }
                is WebSocketEvent.Failure -> {
                    failed = true
                    onFailure?.invoke(event)
                    val codeSuffix = event.responseCode?.let { " (HTTP $it)" } ?: ""
                    val detailPart = event.errorHeader?.takeIf { it.isNotBlank() }
                        ?: event.throwable.message
                        ?: "WebSocket failure"
                    failureReason = if (codeSuffix.isNotEmpty() && !detailPart.contains("HTTP")) {
                        "$detailPart$codeSuffix"
                    } else {
                        detailPart
                    }
                    lastFailureReason = failureReason
                    onStatusChange(LiveConnectionStatus.ERROR, failureReason)
                }
            }
        }

        if (!failed && !connectedInAttempt) {
            failureReason = "Disconnected"
            lastFailureReason = failureReason
        }
        val waitMs = backoff.nextDelayMs(attempt)
        onStatusChange(
            LiveConnectionStatus.RECONNECTING,
            "$failureReason • retrying in ${waitMs / 1000}s"
        )
        attempt++
        delay(waitMs)
    }
}

/**
 * Backpressure coalescer: emits at most one PriceTick per symbol per [windowMs].
 */
fun Flow<PriceTick>.throttlePerInstrument(windowMs: Long = 500L): Flow<PriceTick> = flow {
    val lastEmitted = ConcurrentHashMap<String, Long>()
    collect { tick ->
        val now = tick.timestampMillis
        val prev = lastEmitted[tick.symbol] ?: 0L
        if (now - prev >= windowMs) {
            lastEmitted[tick.symbol] = now
            emit(tick)
        }
    }
}
