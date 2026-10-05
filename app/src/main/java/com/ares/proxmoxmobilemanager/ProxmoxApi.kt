package com.ares.proxmoxmobilemanager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ProxmoxConnection(
    val localUrl: String,
    val remoteUrl: String,
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

    private suspend fun postAction(base: String, connection: ProxmoxConnection, vm: ProxmoxVm, action: String) =
        withContext(Dispatchers.IO) {
            val endpoint = if (vm.isQemu) "qemu" else "lxc"
            post(base, "/api2/json/nodes/${vm.node}/$endpoint/${vm.vmid}/status/$action", connection, 10000)
        }

    private fun post(base: String, path: String, connection: ProxmoxConnection, timeout: Int): JSONObject {
        val url = URL(base.trimEnd('/') + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeout
            readTimeout = timeout
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "PVEAPIToken=${connection.tokenId}=${connection.tokenSecret}")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Proxmox HTTP $code: $body")
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun request(
        base: String,
        path: String,
        connection: ProxmoxConnection,
        timeout: Int
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = URL(base.trimEnd('/') + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeout
            readTimeout = timeout
            requestMethod = "GET"
            setRequestProperty(
                "Authorization",
                "PVEAPIToken=${connection.tokenId}=${connection.tokenSecret}"
            )
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
