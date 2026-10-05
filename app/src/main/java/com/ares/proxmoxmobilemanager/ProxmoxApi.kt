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
