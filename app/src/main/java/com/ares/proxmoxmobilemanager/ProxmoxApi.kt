package com.ares.proxmoxmobilemanager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

data class ProxmoxConnection(
    val localUrl: String,
    val remoteUrl: String,
    val username: String,
    val password: String,
    val tokenId: String,
    val tokenSecret: String
)

data class ProxmoxNode(
    val node: String,
    val status: String,
    val cpu: Double,
    val maxCpu: Int,
    val mem: Long,
    val maxMem: Long,
    val uptime: Long
)


data class ProxmoxStorage(
    val node: String,
    val storage: String,
    val type: String,
    val content: String,
    val active: Boolean,
    val enabled: Boolean,
    val total: Long,
    val used: Long,
    val avail: Long
)

data class ProxmoxVmConfig(
    val entries: List<Pair<String, String>>
)

data class ProxmoxVm(
    val node: String,
    val vmid: Int,
    val name: String,
    val type: String,
    val status: String,
    val cpu: Double,
    val mem: Long,
    val maxMem: Long,
    val maxDisk: Long
) {
    val isRunning: Boolean get() = status.equals("running", ignoreCase = true)
    val isQemu: Boolean get() = type.equals("qemu", ignoreCase = true)
}

class ProxmoxApi {
    private val localTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val localSslSocketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(localTrustManager), java.security.SecureRandom())
        }.socketFactory
    }

    private val localHostnameVerifier = HostnameVerifier { _, _ -> true }

    private fun openConnection(url: URL, base: String, connection: ProxmoxConnection): HttpURLConnection {
        val http = url.openConnection() as HttpURLConnection
        if (base == connection.localUrl.trim().trimEnd('/') && http is HttpsURLConnection) {
            http.sslSocketFactory = localSslSocketFactory
            http.hostnameVerifier = localHostnameVerifier
        }
        return http
    }

    private var localTicket: String? = null
    private var localCsrf: String? = null
    private var localUser: String? = null

    private suspend fun ensureLocalLogin(base: String, connection: ProxmoxConnection) {
        if (base != connection.localUrl.trim().trimEnd('/')) return
        if (connection.username.isBlank() || connection.password.isBlank()) throw IllegalStateException("Za lokalno spajanje upiši korisničko ime i lozinku.")
        if (localTicket != null && localUser == connection.username) return
        withContext(Dispatchers.IO) {
            val url = URL(base.trimEnd('/') + "/api2/json/access/ticket")
            val conn = openConnection(url, base, connection).apply {
                connectTimeout = 5000; readTimeout = 7000; requestMethod = "POST"; doOutput = true
                setRequestProperty("Accept", "application/json"); setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            try {
                val loginUser = connection.username.trim().let { if (it.contains("@")) it else "$it@pam" }
                val form = "username=" + URLEncoder.encode(loginUser, "UTF-8") + "&password=" + URLEncoder.encode(connection.password, "UTF-8")
                conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException(if (code == 401) "Lokalna prijava nije uspjela: korisničko ime ili lozinka nisu ispravni." else "Proxmox prijava HTTP $code: $body")
                val data = JSONObject(body).getJSONObject("data")
                localTicket = data.getString("ticket")
                localCsrf = data.optString("CSRFPreventionToken").ifBlank { null }
                localUser = data.optString("username", connection.username.trim())
            } finally { conn.disconnect() }
        }
    }

    suspend fun getConsoleAuthHeaders(base: String, connection: ProxmoxConnection): Map<String, String> {
        ensureLocalLogin(base, connection)
        return if (base == connection.localUrl.trim().trimEnd('/') && localTicket != null) {
            buildMap {
                put("Cookie", "PVEAuthCookie=" + localTicket)
                localCsrf?.let { put("CSRFPreventionToken", it) }
            }
        } else {
            if (connection.tokenId.isBlank() || connection.tokenSecret.isBlank()) throw IllegalStateException("Za udaljeni pristup upiši API token ID i Secret.")
            mapOf("Authorization" to "PVEAPIToken=" + connection.tokenId + "=" + connection.tokenSecret)
        }
    }

    private fun applyAuth(conn: HttpURLConnection, base: String, connection: ProxmoxConnection) {
        if (base == connection.localUrl.trim().trimEnd('/') && localTicket != null) {
            conn.setRequestProperty("Cookie", "PVEAuthCookie=" + localTicket)
            localCsrf?.let { conn.setRequestProperty("CSRFPreventionToken", it) }
        } else {
            if (connection.tokenId.isBlank() || connection.tokenSecret.isBlank()) throw IllegalStateException("Za udaljeni pristup upiši API token ID i Secret.")
            conn.setRequestProperty("Authorization", "PVEAPIToken=" + connection.tokenId + "=" + connection.tokenSecret)
        }
    }

    suspend fun findReachableBase(connection: ProxmoxConnection): String? {
        val candidates = listOf(connection.localUrl, connection.remoteUrl)
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()

        for (base in candidates) {
            try {
                request(base, "/api2/json/version", connection, 3500)
                return base
            } catch (_: Exception) {
                // Try the next endpoint.
            }
        }
        return null
    }

    suspend fun getServerIdentity(base: String, connection: ProxmoxConnection): String =
        withContext(Dispatchers.IO) {
            try {
                val json = request(base, "/api2/json/cluster/status", connection, 7000)
                val data = json.optJSONArray("data")
                if (data != null && data.length() > 0) {
                    return@withContext (0 until data.length()).map { i ->
                        val item = data.getJSONObject(i)
                        listOf(item.optString("type"), item.optString("name"), item.optString("id")).joinToString(":")
                    }.sorted().joinToString("|")
                }
            } catch (_: Exception) { }
            val nodes = getNodes(base, connection)
            nodes.map { it.node + ":" + it.maxCpu }.sorted().joinToString("|")
        }

    suspend fun getNodes(base: String, connection: ProxmoxConnection): List<ProxmoxNode> =
        withContext(Dispatchers.IO) {
            val json = request(base, "/api2/json/nodes", connection, 7000)
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    add(
                        ProxmoxNode(
                            node = item.optString("node"),
                            status = item.optString("status"),
                            cpu = item.optDouble("cpu"),
                            maxCpu = item.optInt("maxcpu"),
                            mem = item.optLong("mem"),
                            maxMem = item.optLong("maxmem"),
                            uptime = item.optLong("uptime")
                        )
                    )
                }
            }
        }


    suspend fun getVms(base: String, connection: ProxmoxConnection): List<ProxmoxVm> =
        withContext(Dispatchers.IO) {
            val json = request(base, "/api2/json/cluster/resources?type=vm", connection, 7000)
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    val type = item.optString("type")
                    val vmid = item.optInt("vmid", -1)
                    if (vmid > 0 && (type == "qemu" || type == "lxc")) {
                        add(
                            ProxmoxVm(
                                node = item.optString("node"),
                                vmid = vmid,
                                name = item.optString("name", "$type-$vmid"),
                                type = type,
                                status = item.optString("status", "unknown"),
                                cpu = item.optDouble("cpu", 0.0),
                                mem = item.optLong("mem", 0),
                                maxMem = item.optLong("maxmem", 0),
                                maxDisk = item.optLong("maxdisk", 0)
                            )
                        )
                    }
                }
            }.sortedWith(compareBy({ it.node }, { it.vmid }))
        }

    suspend fun getVmConfig(base: String, connection: ProxmoxConnection, vm: ProxmoxVm): ProxmoxVmConfig =
        withContext(Dispatchers.IO) {
            val endpoint = if (vm.isQemu) "qemu" else "lxc"
            val json = request(base, "/api2/json/nodes/${URLEncoder.encode(vm.node, "UTF-8")}/${endpoint}/${vm.vmid}/config", connection, 7000)
            val data = json.optJSONObject("data") ?: return@withContext ProxmoxVmConfig(emptyList())
            val keys = data.keys().asSequence().toList().sorted()
            ProxmoxVmConfig(keys.map { key ->
                val value = data.opt(key)
                key to when (value) {
                    null -> ""
                    value == org.json.JSONObject.NULL -> ""
                    else -> value.toString()
                }
            })
        }

    suspend fun startVm(base: String, connection: ProxmoxConnection, vm: ProxmoxVm) {
        postAction(base, connection, vm, "start")
    }

    suspend fun stopVm(base: String, connection: ProxmoxConnection, vm: ProxmoxVm) {
        postAction(base, connection, vm, "stop")
    }

    suspend fun rebootVm(base: String, connection: ProxmoxConnection, vm: ProxmoxVm) { postAction(base, connection, vm, "reboot") }
    suspend fun shutdownVm(base: String, connection: ProxmoxConnection, vm: ProxmoxVm) { postAction(base, connection, vm, "shutdown") }
    suspend fun resetVm(base: String, connection: ProxmoxConnection, vm: ProxmoxVm) {
        if (!vm.isQemu) throw IllegalStateException("Reset je dostupan za QEMU VM.")
        postAction(base, connection, vm, "reset")
    }

    suspend fun getStorage(base: String, connection: ProxmoxConnection): List<ProxmoxStorage> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<ProxmoxStorage>()
            for (node in getNodes(base, connection)) {
                try {
                    val json = request(base, "/api2/json/nodes/${node.node}/storage", connection, 7000)
                    val data = json.optJSONArray("data") ?: continue
                    for (i in 0 until data.length()) {
                        val item = data.getJSONObject(i)
                        result += ProxmoxStorage(node.node, item.optString("storage"), item.optString("type"), item.optString("content"), item.optBoolean("active"), item.optBoolean("enabled"), item.optLong("total"), item.optLong("used"), item.optLong("avail"))
                    }
                } catch (_: Exception) { }
            }
            result
        }

    suspend fun createSnapshot(base: String, connection: ProxmoxConnection, vm: ProxmoxVm, snapName: String, description: String = "") {
        withContext(Dispatchers.IO) {
            val endpoint = if (vm.isQemu) "qemu" else "lxc"
            val params = "snapname=" + URLEncoder.encode(snapName, "UTF-8") +
                if (description.isBlank()) "" else "&description=" + URLEncoder.encode(description, "UTF-8")
            runTaskAndWait(base, connection, vm.node, post(base, "/api2/json/nodes/${vm.node}/$endpoint/${vm.vmid}/snapshot", connection, 10000, params))
        }
    }

    private suspend fun postAction(base: String, connection: ProxmoxConnection, vm: ProxmoxVm, action: String) =
        withContext(Dispatchers.IO) {
            val endpoint = if (vm.isQemu) "qemu" else "lxc"
            runTaskAndWait(base, connection, vm.node, post(base, "/api2/json/nodes/${vm.node}/$endpoint/${vm.vmid}/status/$action", connection, 10000))
        }

    private suspend fun runTaskAndWait(
        base: String,
        connection: ProxmoxConnection,
        node: String,
        response: JSONObject
    ) {
        val upid = response.optString("data")
        if (upid.isBlank()) return

        withContext(Dispatchers.IO) {
            repeat(90) {
                val encoded = URLEncoder.encode(upid, "UTF-8")
                val task = request(
                    base,
                    "/api2/json/nodes/${URLEncoder.encode(node, "UTF-8")}/tasks/$encoded/status",
                    connection,
                    10000
                )
                val data = task.optJSONObject("data")
                val status = data?.optString("status").orEmpty()
                val exitStatus = data?.optString("exitstatus").orEmpty()

                if (status == "stopped") {
                    if (exitStatus.isNotBlank() && exitStatus != "OK") {
                        throw IllegalStateException("Proxmox zadatak nije uspio: $exitStatus")
                    }
                    return@withContext
                }

                delay(1000)
            }
            throw IllegalStateException("Proxmox zadatak traje predugo (više od 90 sekundi).")
        }
    }

    private suspend fun post(base: String, path: String, connection: ProxmoxConnection, timeout: Int, formBody: String = ""): JSONObject {
        ensureLocalLogin(base, connection)
        return withContext(Dispatchers.IO) {
        val url = URL(base.trimEnd('/') + path)
        val conn = openConnection(url, base, connection).apply {
            connectTimeout = timeout
            readTimeout = timeout
            requestMethod = "POST"
            doOutput = true
            applyAuth(this, base, connection)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            if (formBody.isNotBlank()) conn.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Proxmox HTTP $code: $body")
            JSONObject(body)
        } finally {
            conn.disconnect()
        }
        }
    }

    private suspend fun request(
        base: String,
        path: String,
        connection: ProxmoxConnection,
        timeout: Int
    ): JSONObject {
        ensureLocalLogin(base, connection)
        return withContext(Dispatchers.IO) {
        val url = URL(base.trimEnd('/') + path)
        val conn = openConnection(url, base, connection).apply {
            connectTimeout = timeout
            readTimeout = timeout
            requestMethod = "GET"
            applyAuth(this, base, connection)
            setRequestProperty("Accept", "application/json")
        }

        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Proxmox HTTP $code: $body")
            }
            JSONObject(body)
        } finally {
            conn.disconnect()
        }
        }
    }
}
