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

data class ProxmoxClusterStatus(
    val clustered: Boolean,
    val name: String,
    val version: String,
    val quorate: Boolean,
    val nodes: List<Pair<String, String>>
 )



data class ProxmoxTask(
    val upid: String,
    val type: String,
    val status: String,
    val exitStatus: String,
    val user: String,
    val startTime: Long,
    val endTime: Long
)

data class ProxmoxReplicationJob(
    val id: String,
    val type: String,
    val target: String,
    val schedule: String,
    val state: String,
    val lastSync: Long,
    val duration: Long,
    val failCount: Int,
    val error: String
)
data class ProxmoxFirewallRule(
    val pos: Int,
    val action: String,
    val type: String,
    val iface: String,
    val source: String,
    val dest: String,
    val proto: String,
    val dport: String,
    val sport: String,
    val comment: String,
    val enable: Boolean
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

    suspend fun getClusterStatus(base: String, connection: ProxmoxConnection): ProxmoxClusterStatus =
        withContext(Dispatchers.IO) {
            val json = request(base, "/api2/json/cluster/status", connection, 7000)
            val data = json.optJSONArray("data") ?: return@withContext ProxmoxClusterStatus(false, "", "", true, emptyList())
            var clusterName = ""
            var version = ""
            var quorate = true
            val members = mutableListOf<Pair<String, String>>()
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                when (item.optString("type")) {
                    "cluster" -> {
                        clusterName = item.optString("name")
                        version = item.optString("version")
                        quorate = item.optBoolean("quorate", true)
                    }
                    "node" -> members += item.optString("name") to item.optString("online", "0")
                }
            }
            ProxmoxClusterStatus(clusterName.isNotBlank(), clusterName, version, quorate, members)
        }
    suspend fun createCluster(base: String, connection: ProxmoxConnection, name: String, link0: String = "") = withContext(Dispatchers.IO) {
        val form = "clustername=" + URLEncoder.encode(name.trim(), "UTF-8")
        requestWrite(base, "/api2/json/cluster/config", connection, "POST", 15000, form)
    }

    suspend fun joinCluster(base: String, connection: ProxmoxConnection, hostname: String, password: String, fingerprint: String, link0: String = "", force: Boolean = false) = withContext(Dispatchers.IO) {
        val parts = mutableListOf("hostname="+URLEncoder.encode(hostname.trim(),"UTF-8"),"password="+URLEncoder.encode(password,"UTF-8"),"fingerprint="+URLEncoder.encode(fingerprint.trim(),"UTF-8"))
        if(link0.isNotBlank()) parts += "link0="+URLEncoder.encode(link0.trim(),"UTF-8")
        if(force) parts += "force=1"
        requestWrite(base, "/api2/json/cluster/config/join", connection, "POST", 20000, parts.joinToString("&"))
    }

    suspend fun getClusterJoinInfo(base: String, connection: ProxmoxConnection): JSONObject =
        withContext(Dispatchers.IO) {
            request(base, "/api2/json/cluster/config/join", connection, 7000).optJSONObject("data") ?: JSONObject()
        }

    suspend fun getNodeSyslog(base: String, connection: ProxmoxConnection, node: ProxmoxNode): List<String> =
        withContext(Dispatchers.IO) {
            val json = request(base, "/api2/json/nodes/${URLEncoder.encode(node.node, "UTF-8")}/syslog?limit=200", connection, 7000)
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    add(item.optString("t", item.optString("msg", item.toString())))
                }
            }
        }

    suspend fun getNodeAptUpdates(base: String, connection: ProxmoxConnection, node: ProxmoxNode): List<Pair<String,String>> =
        withContext(Dispatchers.IO) {
            val json = request(base, "/api2/json/nodes/${URLEncoder.encode(node.node, "UTF-8")}/apt/update", connection, 10000)
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    add(item.optString("Package") to item.optString("Version"))
                }
            }
        }

    suspend fun getNodeDisks(base: String, connection: ProxmoxConnection, node: ProxmoxNode): List<String> =
        withContext(Dispatchers.IO) {
            val path = "/api2/json/nodes/${URLEncoder.encode(node.node, "UTF-8")}/disks/list"
            val data = request(base, path, connection, 10000).optJSONArray("data") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val path = item.optString("devpath", item.optString("path", "Nepoznat uređaj"))
                    val model = item.optString("model", "Model nije naveden")
                    val size = item.optLong("size", 0L)
                    val sizeText = if (size > 0L) String.format(java.util.Locale.getDefault(), "%.1f GB", size / 1_000_000_000.0) else "Veličina nije dostupna"
                    val type = item.optString("type", "disk")
                    val usage = if (item.optBoolean("used")) "U upotrebi" else "Slobodan / neoznačen"
                    add("$path  •  $model  •  $sizeText  •  $type  •  $usage")
                }
            }
        }

    suspend fun getNodeNetwork(base: String, connection: ProxmoxConnection, node: ProxmoxNode): List<String> =
        withContext(Dispatchers.IO) {
            val path = "/api2/json/nodes/${URLEncoder.encode(node.node, "UTF-8")}/network"
            val data = request(base, path, connection, 10000).optJSONArray("data")
                ?: return@withContext emptyList()
            buildList {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val iface = item.optString("iface", "nepoznato")
                    val type = item.optString("type", "interface")
                    val active = if (item.optBoolean("active", false)) "aktivno" else "neaktivno"
                    val autostart = if (item.optBoolean("autostart", false)) "autostart" else "bez autostarta"
                    val address = listOf(item.optString("address"), item.optString("netmask"))
                        .filter { it.isNotBlank() }.joinToString("/")
                    val bridge = item.optString("bridge_ports").takeIf { it.isNotBlank() }?.let { " • portovi: $it" }.orEmpty()
                    add("$iface • $type • $active • $autostart" +
                        (if (address.isNotBlank()) " • $address" else "") + bridge)
                }
            }.sorted()
        }

    suspend fun controlNode(base: String, connection: ProxmoxConnection, node: ProxmoxNode, action: String) {
        require(action == "reboot" || action == "shutdown") { "Nepoznata radnja za node." }
        post(base, "/api2/json/nodes/${URLEncoder.encode(node.node, "UTF-8")}/status", connection, 10000, "command=" + URLEncoder.encode(action, "UTF-8"))
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
                key to if (value == null || value == org.json.JSONObject.NULL) "" else value.toString()
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


    suspend fun updateVmConfig(base: String, connection: ProxmoxConnection, vm: ProxmoxVm, key: String, value: String) {
        withContext(Dispatchers.IO) {
            val endpoint = if (vm.isQemu) "qemu" else "lxc"
            val form = URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
            requestWrite(base, "/api2/json/nodes/${URLEncoder.encode(vm.node, "UTF-8")}/$endpoint/${vm.vmid}/config", connection, "PUT", 10000, form)
        }
    }

    suspend fun getSnapshots(base: String, connection: ProxmoxConnection, vm: ProxmoxVm): List<Pair<String,String>> =
        withContext(Dispatchers.IO) {
            val endpoint = if (vm.isQemu) "qemu" else "lxc"
            val json = request(base, "/api2/json/nodes/${URLEncoder.encode(vm.node, "UTF-8")}/$endpoint/${vm.vmid}/snapshot", connection, 7000)
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until data.length()) {
                    val item=data.getJSONObject(i)
                    add(item.optString("name") to item.optString("description"))
                }
            }
        }

    suspend fun deleteSnapshot(base: String, connection: ProxmoxConnection, vm: ProxmoxVm, name: String) {
        withContext(Dispatchers.IO) {
            val endpoint = if (vm.isQemu) "qemu" else "lxc"
            val path="/api2/json/nodes/${URLEncoder.encode(vm.node, "UTF-8")}/$endpoint/${vm.vmid}/snapshot/${URLEncoder.encode(name, "UTF-8")}"
            runTaskAndWait(base, connection, vm.node, requestWrite(base,path,connection,"DELETE",10000))
        }
    }

    suspend fun rollbackSnapshot(base: String, connection: ProxmoxConnection, vm: ProxmoxVm, name: String) {
        withContext(Dispatchers.IO) {
            val endpoint = if (vm.isQemu) "qemu" else "lxc"
            val path="/api2/json/nodes/${URLEncoder.encode(vm.node, "UTF-8")}/$endpoint/${vm.vmid}/snapshot/${URLEncoder.encode(name, "UTF-8")}/rollback"
            runTaskAndWait(base, connection, vm.node, requestWrite(base,path,connection,"POST",10000))
        }
    }

    suspend fun cloneVm(base: String, connection: ProxmoxConnection, vm: ProxmoxVm, newId: Int, newName: String, full: Boolean) {
        withContext(Dispatchers.IO) {
            if (!vm.isQemu) throw IllegalStateException("Clone je trenutno omogućen za QEMU VM.")
            val form="newid=$newId&name="+URLEncoder.encode(newName,"UTF-8")+"&full="+if(full)"1" else "0"
            runTaskAndWait(base, connection, vm.node, post(base,"/api2/json/nodes/${vm.node}/qemu/${vm.vmid}/clone",connection,10000,form))
        }
    }

    suspend fun migrateVm(base: String, connection: ProxmoxConnection, vm: ProxmoxVm, target: String, online: Boolean) {
        withContext(Dispatchers.IO) {
            val endpoint=if(vm.isQemu)"qemu" else "lxc"
            val form="target="+URLEncoder.encode(target,"UTF-8")+"&online="+if(online)"1" else "0"
            runTaskAndWait(base,connection,target,post(base,"/api2/json/nodes/${vm.node}/$endpoint/${vm.vmid}/status/migrate",connection,10000,form))
        }
    }

    suspend fun getNodeTasks(base: String, connection: ProxmoxConnection, node: String, limit: Int = 100): List<ProxmoxTask> = withContext(Dispatchers.IO) {
        val encodedNode = URLEncoder.encode(node, "UTF-8")
        val json = request(base, "/api2/json/nodes/$encodedNode/tasks?limit=$limit", connection, 7000)
        val data = json.optJSONArray("data") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until data.length()) {
                val x = data.getJSONObject(i)
                add(ProxmoxTask(x.optString("upid"), x.optString("type"), x.optString("status"), x.optString("exitstatus"), x.optString("user"), x.optLong("starttime"), x.optLong("endtime")))
            }
        }
    }

    suspend fun getTasks(base: String, connection: ProxmoxConnection, vm: ProxmoxVm, limit: Int = 100): List<ProxmoxTask> = withContext(Dispatchers.IO) {
        val endpoint = if (vm.isQemu) "qemu" else "lxc"
        val json = request(base, "/api2/json/nodes/${URLEncoder.encode(vm.node, "UTF-8")}/$endpoint/${vm.vmid}/tasks?limit=$limit", connection, 7000)
        val data = json.optJSONArray("data") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until data.length()) {
                val x = data.getJSONObject(i)
                add(ProxmoxTask(x.optString("upid"), x.optString("type"), x.optString("status"), x.optString("exitstatus"), x.optString("user"), x.optLong("starttime"), x.optLong("endtime")))
            }
        }
    }

    suspend fun getReplicationJobs(base: String, connection: ProxmoxConnection): List<ProxmoxReplicationJob> = withContext(Dispatchers.IO) {
        val json = request(base, "/api2/json/cluster/replication", connection, 7000)
        val data = json.optJSONArray("data") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until data.length()) {
                val x = data.getJSONObject(i)
                add(ProxmoxReplicationJob(
                    id = x.optString("id"),
                    type = x.optString("type"),
                    target = x.optString("target"),
                    schedule = x.optString("schedule"),
                    state = x.optString("state", x.optString("status")),
                    lastSync = x.optLong("last_sync"),
                    duration = x.optLong("duration"),
                    failCount = x.optInt("fail_count"),
                    error = x.optString("error")
                ))
            }
        }
    }

    suspend fun getFirewallRules(base: String, connection: ProxmoxConnection, vm: ProxmoxVm): List<ProxmoxFirewallRule> = withContext(Dispatchers.IO) {
        val endpoint = if (vm.isQemu) "qemu" else "lxc"
        val json = request(base, "/api2/json/nodes/${URLEncoder.encode(vm.node, "UTF-8")}/$endpoint/${vm.vmid}/firewall/rules", connection, 7000)
        val data = json.optJSONArray("data") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until data.length()) {
                val x = data.getJSONObject(i)
                add(ProxmoxFirewallRule(x.optInt("pos"),x.optString("action"),x.optString("type"),x.optString("iface"),x.optString("source"),x.optString("dest"),x.optString("proto"),x.optString("dport"),x.optString("sport"),x.optString("comment"),x.optBoolean("enable", true)))
            }
        }
    }

    suspend fun addFirewallRule(base: String, connection: ProxmoxConnection, vm: ProxmoxVm, action: String, type: String = "in", enable: Boolean = true, comment: String = "", iface: String = "", source: String = "", dest: String = "", proto: String = "", dport: String = "", sport: String = "") {
        withContext(Dispatchers.IO) {
            val endpoint = if (vm.isQemu) "qemu" else "lxc"
            val parts = mutableListOf("action=" + URLEncoder.encode(action, "UTF-8"), "type=" + URLEncoder.encode(type, "UTF-8"), "enable=" + if (enable) "1" else "0")
            listOf("comment" to comment, "iface" to iface, "source" to source, "dest" to dest, "proto" to proto, "dport" to dport, "sport" to sport).forEach { (key, value) ->
                if (value.isNotBlank()) parts += key + "=" + URLEncoder.encode(value, "UTF-8")
            }
            val path = "/api2/json/nodes/" + URLEncoder.encode(vm.node, "UTF-8") + "/" + endpoint + "/" + vm.vmid + "/firewall/rules"
            requestWrite(base, path, connection, "POST", 10000, parts.joinToString("&"))
        }
    }

    suspend fun setFirewallRuleEnabled(base: String, connection: ProxmoxConnection, vm: ProxmoxVm, pos: Int, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            val endpoint = if (vm.isQemu) "qemu" else "lxc"
            requestWrite(base, "/api2/json/nodes/${URLEncoder.encode(vm.node, "UTF-8")}/$endpoint/${vm.vmid}/firewall/rules/$pos", connection, "PUT", 10000, "enable=" + if (enabled) "1" else "0")
        }
    }

    suspend fun deleteFirewallRule(base: String, connection: ProxmoxConnection, vm: ProxmoxVm, pos: Int) {
        withContext(Dispatchers.IO) {
            val endpoint = if (vm.isQemu) "qemu" else "lxc"
            requestWrite(base, "/api2/json/nodes/${URLEncoder.encode(vm.node, "UTF-8")}/$endpoint/${vm.vmid}/firewall/rules/$pos", connection, "DELETE", 10000)
        }
    }

    suspend fun backupVm(base: String, connection: ProxmoxConnection, vm: ProxmoxVm, storage: String? = null) {
        withContext(Dispatchers.IO) {
            val endpoint = if (vm.isQemu) "qemu" else "lxc"
            val params = storage?.trim()?.takeIf { it.isNotBlank() }?.let {
                "storage=" + URLEncoder.encode(it, "UTF-8")
            } ?: ""
            runTaskAndWait(base, connection, vm.node, post(base, "/api2/json/nodes/" + URLEncoder.encode(vm.node, "UTF-8") + "/" + endpoint + "/" + vm.vmid + "/vzdump", connection, 15000, params))
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


    private suspend fun requestWrite(base: String, path: String, connection: ProxmoxConnection, method: String, timeout: Int, formBody: String = ""): JSONObject {
        ensureLocalLogin(base, connection)
        return withContext(Dispatchers.IO) {
            val url=URL(base.trimEnd('/')+path)
            val conn=openConnection(url,base,connection).apply {
                connectTimeout=timeout; readTimeout=timeout; requestMethod=method; doOutput=method=="POST" || method=="PUT"
                applyAuth(this,base,connection)
                setRequestProperty("Accept","application/json")
                if (doOutput) setRequestProperty("Content-Type","application/x-www-form-urlencoded")
            }
            try {
                if(formBody.isNotBlank()) conn.outputStream.use{it.write(formBody.toByteArray(Charsets.UTF_8))}
                val code=conn.responseCode
                val stream=if(code in 200..299)conn.inputStream else conn.errorStream
                val body=stream?.bufferedReader()?.use{it.readText()}.orEmpty()
                if(code !in 200..299) throw IllegalStateException("Proxmox HTTP $code: $body")
                JSONObject(body)
            } finally { conn.disconnect() }
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
