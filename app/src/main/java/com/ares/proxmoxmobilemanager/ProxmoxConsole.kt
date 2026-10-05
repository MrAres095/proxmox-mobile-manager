package com.ares.proxmoxmobilemanager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ProxmoxConsole(private val client: OkHttpClient = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()) {
 private var socket: WebSocket? = null
 suspend fun open(base: String, connection: ProxmoxConnection, vm: ProxmoxVm, onData: (ByteArray)->Unit, onClosed: (String?)->Unit, onError: (Throwable)->Unit) {
  val type = if (vm.isQemu) "qemu" else "lxc"
  val proxyUrl = base.trimEnd('/') + "/api2/json/nodes/" + vm.node + "/" + type + "/" + vm.vmid + "/termproxy"
  val session = withContext(Dispatchers.IO) {
   val req = Request.Builder().url(proxyUrl).post(RequestBody.create(null, ByteArray(0))).header("Authorization", "PVEAPIToken=" + connection.tokenId + "=" + connection.tokenSecret).build()
   client.newCall(req).execute().use { res ->
    val body = res.body?.string().orEmpty()
    if (!res.isSuccessful) throw IllegalStateException("termproxy HTTP " + res.code + ": " + body)
    val data = JSONObject(body).getJSONObject("data")
    Triple(data.getInt("port"), data.getString("ticket"), data.optString("user", connection.tokenId.substringBefore('!')))
   }
  }
  val wsBase = base.replaceFirst(Regex("^https://"), "wss://").replaceFirst(Regex("^http://"), "ws://").trimEnd('/')
  val wsUrl = wsBase + "/api2/json/nodes/" + vm.node + "/" + type + "/" + vm.vmid + "/vncwebsocket?port=" + session.first + "&vncticket=" + java.net.URLEncoder.encode(session.second, "UTF-8")
  val req = Request.Builder().url(wsUrl).header("Authorization", "PVEAPIToken=" + connection.tokenId + "=" + connection.tokenSecret).build()
  socket = client.newWebSocket(req, object : WebSocketListener() {
   override fun onOpen(ws: WebSocket, res: Response) { ws.send(session.third + ":" + session.second + "\n"); ws.send("1:120:32:") }
   override fun onMessage(ws: WebSocket, b: okio.ByteString) { onData(b.toByteArray()) }
   override fun onMessage(ws: WebSocket, t: String) { onData(t.toByteArray(Charsets.UTF_8)) }
   override fun onFailure(ws: WebSocket, t: Throwable, res: Response?) { onError(t) }
   override fun onClosed(ws: WebSocket, code: Int, reason: String) { onClosed(reason.ifBlank { null }) }
  })
 }
 fun send(text: String) { val b = text.toByteArray(Charsets.UTF_8); socket?.send("0:" + b.size + ":" + text) }
 fun resize(cols: Int, rows: Int) { socket?.send("1:" + cols + ":" + rows + ":") }
 fun ping() { socket?.send("2") }
 fun close() { socket?.close(1000, "closed"); socket = null }
}

 suspend fun upgradeNode(base: String, connection: ProxmoxConnection, node: ProxmoxNode, onData: (ByteArray)->Unit): Result<Unit> {
  return runCatching {
   val proxyUrl = base.trimEnd('/') + "/api2/json/nodes/" + node.node + "/termproxy?cmd=upgrade"
   val session = withContext(Dispatchers.IO) {
    val req = Request.Builder().url(proxyUrl)
     .post(RequestBody.create(null, ByteArray(0)))
     .header("Authorization", "PVEAPIToken=" + connection.tokenId + "=" + connection.tokenSecret)
     .build()
    client.newCall(req).execute().use { res ->
     val body = res.body?.string().orEmpty()
     if (!res.isSuccessful) throw IllegalStateException("termproxy HTTP " + res.code + ": " + body)
     val data = JSONObject(body).getJSONObject("data")
     Triple(data.getInt("port"), data.getString("ticket"), data.optString("user", connection.tokenId.substringBefore('!')))
    }
   }
   val wsBase = base.replaceFirst(Regex("^https://"), "wss://").replaceFirst(Regex("^http://"), "ws://").trimEnd('/')
   val wsUrl = wsBase + "/api2/json/nodes/" + node.node + "/vncwebsocket?port=" + session.first + "&vncticket=" + java.net.URLEncoder.encode(session.second, "UTF-8")
   val done = kotlinx.coroutines.CompletableDeferred<Unit>()
   val req = Request.Builder().url(wsUrl)
    .header("Authorization", "PVEAPIToken=" + connection.tokenId + "=" + connection.tokenSecret).build()
   val ws = client.newWebSocket(req, object : WebSocketListener() {
    override fun onOpen(ws: WebSocket, res: Response) {
     ws.send(session.third + ":" + session.second + "\n")
     ws.send("1:120:32:")
    }
    override fun onMessage(ws: WebSocket, b: okio.ByteString) { onData(b.toByteArray()) }
    override fun onMessage(ws: WebSocket, t: String) { onData(t.toByteArray(Charsets.UTF_8)) }
    override fun onFailure(ws: WebSocket, t: Throwable, res: Response?) { done.completeExceptionally(t) }
    override fun onClosed(ws: WebSocket, code: Int, reason: String) { if (!done.isCompleted) done.complete(Unit) }
   })
   done.await()
   ws.close(1000, "upgrade complete")
  }
 }

 fun sendRaw(text: String) {
  val b = text.toByteArray(Charsets.UTF_8)
  socket?.send("0:" + b.size + ":" + text)
 }
