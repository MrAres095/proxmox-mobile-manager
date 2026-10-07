package com.ares.proxmoxmobilemanager

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

class ProxmoxConsole(
    private val api: ProxmoxApi = ProxmoxApi()
) {
    private var socket: WebSocket? = null

    private val localTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val localSslSocketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(localTrustManager), SecureRandom())
        }.socketFactory
    }

    private val localHostnameVerifier = HostnameVerifier { _, _ -> true }

    private fun isLocal(base: String, connection: ProxmoxConnection): Boolean =
        base.trim().trimEnd('/') == connection.localUrl.trim().trimEnd('/')

    private fun clientFor(base: String, connection: ProxmoxConnection): OkHttpClient =
        OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .apply {
                if (isLocal(base, connection)) {
                    sslSocketFactory(localSslSocketFactory, localTrustManager)
                    hostnameVerifier(localHostnameVerifier)
                }
            }
            .build()

    private fun authUser(connection: ProxmoxConnection, sessionUser: String): String =
        sessionUser.ifBlank {
            connection.username.trim().let { if (it.contains("@")) it else "$it@pam" }
        }

    suspend fun open(
        base: String,
        connection: ProxmoxConnection,
        vm: ProxmoxVm,
        onData: (ByteArray) -> Unit,
        onClosed: (String?) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        close()
        val client = clientFor(base, connection)
        val type = if (vm.isQemu) "qemu" else "lxc"
        val proxyUrl = base.trimEnd('/') + "/api2/json/nodes/" + vm.node + "/" + type + "/" + vm.vmid + "/termproxy"
        val authHeaders = api.getConsoleAuthHeaders(base, connection)

        try {
            val session = withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url(proxyUrl)
                    .post(RequestBody.create(null, ByteArray(0)))
                    .apply { authHeaders.forEach { (k, v) -> header(k, v) } }
                    .build()
                client.newCall(req).execute().use { res ->
                    val body = res.body?.string().orEmpty()
                    if (!res.isSuccessful) throw IllegalStateException("termproxy HTTP " + res.code + ": " + body)
                    val data = JSONObject(body).getJSONObject("data")
                    Triple(
                        data.getInt("port"),
                        data.getString("ticket"),
                        authUser(connection, data.optString("user"))
                    )
                }
            }

            val wsBase = base.replaceFirst(Regex("^https://"), "wss://")
                .replaceFirst(Regex("^http://"), "ws://")
                .trimEnd('/')
            val wsUrl = wsBase + "/api2/json/nodes/" + vm.node + "/" + type + "/" + vm.vmid +
                "/vncwebsocket?port=" + session.first + "&vncticket=" + URLEncoder.encode(session.second, "UTF-8")
            val req = Request.Builder()
                .url(wsUrl)
                .apply { authHeaders.forEach { (k, v) -> header(k, v) } }
                .build()

            socket = client.newWebSocket(req, listener(session.third, session.second, onData, onClosed, onError))
        } catch (e: Throwable) {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            throw e
        }
    }

    suspend fun upgradeNode(
        base: String,
        connection: ProxmoxConnection,
        node: ProxmoxNode,
        onData: (ByteArray) -> Unit
    ): Result<Unit> = runCatching {
        val client = clientFor(base, connection)
        val authHeaders = api.getConsoleAuthHeaders(base, connection)
        val proxyUrl = base.trimEnd('/') + "/api2/json/nodes/" + node.node + "/termproxy?cmd=upgrade"
        val session = withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(proxyUrl)
                .post(RequestBody.create(null, ByteArray(0)))
                .apply { authHeaders.forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw IllegalStateException("termproxy HTTP " + res.code + ": " + body)
                val data = JSONObject(body).getJSONObject("data")
                Triple(
                    data.getInt("port"),
                    data.getString("ticket"),
                    authUser(connection, data.optString("user"))
                )
            }
        }

        val wsBase = base.replaceFirst(Regex("^https://"), "wss://")
            .replaceFirst(Regex("^http://"), "ws://")
            .trimEnd('/')
        val wsUrl = wsBase + "/api2/json/nodes/" + node.node +
            "/vncwebsocket?port=" + session.first + "&vncticket=" + URLEncoder.encode(session.second, "UTF-8")
        val done = CompletableDeferred<Unit>()
        val req = Request.Builder()
            .url(wsUrl)
            .apply { authHeaders.forEach { (k, v) -> header(k, v) } }
            .build()

        client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, res: Response) {
                ws.send(session.third + ":" + session.second + "\n")
                ws.send("1:120:32:")
            }
            override fun onMessage(ws: WebSocket, b: okio.ByteString) {
                onData(b.toByteArray())
            }
            override fun onMessage(ws: WebSocket, t: String) {
                onData(t.toByteArray(Charsets.UTF_8))
            }
            override fun onFailure(ws: WebSocket, t: Throwable, res: Response?) {
                if (!done.isCompleted) done.completeExceptionally(t)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (!done.isCompleted) done.complete(Unit)
            }
        })
        done.await()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private fun listener(
        user: String,
        ticket: String,
        onData: (ByteArray) -> Unit,
        onClosed: (String?) -> Unit,
        onError: (Throwable) -> Unit
    ) = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, res: Response) {
            ws.send(user + ":" + ticket + "\n")
            ws.send("1:120:32:")
        }
        override fun onMessage(ws: WebSocket, b: okio.ByteString) {
            onData(b.toByteArray())
        }
        override fun onMessage(ws: WebSocket, t: String) {
            onData(t.toByteArray(Charsets.UTF_8))
        }
        override fun onFailure(ws: WebSocket, t: Throwable, res: Response?) {
            onError(t)
        }
        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            onClosed(reason.ifBlank { null })
        }
    }

    fun send(text: String) {
        val b = text.toByteArray(Charsets.UTF_8)
        socket?.send("0:" + b.size + ":" + text)
    }

    fun sendRaw(text: String) = send(text)

    fun resize(cols: Int, rows: Int) {
        socket?.send("1:" + cols + ":" + rows + ":")
    }

    fun ping() {
        socket?.send("2")
    }

    fun close() {
        socket?.close(1000, "closed")
        socket = null
    }
}
