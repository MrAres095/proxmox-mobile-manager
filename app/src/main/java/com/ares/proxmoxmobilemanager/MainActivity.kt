package com.ares.proxmoxmobilemanager

import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private val api = ProxmoxApi()
private val console = ProxmoxConsole()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ProxmoxApp() }
    }
}

@Composable
fun ProxmoxApp() {
    val context = LocalContext.current
    val uiPrefs = remember { context.getSharedPreferences("ui_preferences", Context.MODE_PRIVATE) }
    var theme by remember { mutableStateOf(uiPrefs.getString("theme", "system") ?: "system") }
    val dark = theme != "light"
    val appColors = if (dark) darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF18C6D8),
        onPrimary = androidx.compose.ui.graphics.Color(0xFF00191D),
        secondary = androidx.compose.ui.graphics.Color(0xFF35D07F),
        background = androidx.compose.ui.graphics.Color(0xFF05090C),
        surface = androidx.compose.ui.graphics.Color(0xFF10181D),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1A252C),
        onSurface = androidx.compose.ui.graphics.Color(0xFFE4F0F4),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF9BB0B9)
    ) else lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF007C91),
        secondary = androidx.compose.ui.graphics.Color(0xFF16894F)
    )
    MaterialTheme(colorScheme = appColors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            var showSettings by remember { mutableStateOf(true) }
            var showClusterManager by remember { mutableStateOf(false) }
            var showReplication by remember { mutableStateOf(false) }
            var selectedNode by remember { mutableStateOf<ProxmoxNode?>(null) }
            var shellNode by remember { mutableStateOf<ProxmoxNode?>(null) }
            var replicationJobs by remember { mutableStateOf<List<ProxmoxReplicationJob>>(emptyList()) }
            var replicationLoading by remember { mutableStateOf(false) }
            var showUiSettings by remember { mutableStateOf(false) }
            var language by remember { mutableStateOf(uiPrefs.getString("language", "hr") ?: "hr") }
            var consoleVm by remember { mutableStateOf<ProxmoxVm?>(null) }
            var connectedBase by remember { mutableStateOf<String?>(null) }
            var nodes by remember { mutableStateOf<List<ProxmoxNode>>(emptyList()) }
            var vms by remember { mutableStateOf<List<ProxmoxVm>>(emptyList()) }
            var storage by remember { mutableStateOf<List<ProxmoxStorage>>(emptyList()) }
            var clusterStatus by remember { mutableStateOf<ProxmoxClusterStatus?>(null) }
            var vmLoading by remember { mutableStateOf(false) }
            var loading by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var updatingAll by remember { mutableStateOf(false) }
            var updateStatuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("proxmox_connection", Context.MODE_PRIVATE) }
            var profiles by remember { mutableStateOf(ProxmoxServerProfiles.load(context)) }
            var selectedProfile by remember { mutableStateOf(profiles.firstOrNull()?.name ?: "Proxmox") }
            var connection by remember { mutableStateOf(ProxmoxConnection(prefs.getString("localUrl", "") ?: "", prefs.getString("remoteUrl", "") ?: "", prefs.getString("username", "") ?: "", prefs.getString("password", "") ?: "", prefs.getString("tokenId", "") ?: "", prefs.getString("tokenSecret", "") ?: "")) }
            val scope = rememberCoroutineScope()

            if (consoleVm != null && connectedBase != null) { ConsoleScreen(connectedBase!!, connection, consoleVm!!, { console.close(); consoleVm=null }) } else if (showSettings || connectedBase == null) {
                ConnectionScreen(connection, loading, error, profiles, selectedProfile, onThemeSettings = { showUiSettings = true }) { profileName, newConnection ->
                    selectedProfile = profileName
                    connection = newConnection
                    ProxmoxServerProfiles.save(context, ProxmoxServerProfile(profileName, newConnection))
                    profiles = ProxmoxServerProfiles.load(context)
                    prefs.edit().putString("localUrl", newConnection.localUrl).putString("remoteUrl", newConnection.remoteUrl).putString("username", newConnection.username).putString("password", newConnection.password).putString("tokenId", newConnection.tokenId).putString("tokenSecret", newConnection.tokenSecret).apply()
                    loading = true
                    error = null
                    scope.launch {
                        try {
                            val base = api.findReachableBase(newConnection)
                                ?: throw IllegalStateException("Nijedan Proxmox URL nije dostupan.")
                            connectedBase = base
                            nodes = api.getNodes(base, newConnection)
                            // Učitaj stvarne VM/LXC resurse i storage odmah nakon prijave.
                            // Prije su se učitavali tek nakon ručnog osvježavanja.
                            vms = api.getVms(base, newConnection)
                            storage = api.getStorage(base, newConnection)
                            clusterStatus = api.getClusterStatus(base, newConnection)
                            showSettings = false
                        } catch (e: Exception) {
                            error = e.message ?: "Greška pri povezivanju."
                        } finally {
                            loading = false
                        }
                    }
                }
            } else {
                Dashboard(
                    base = connectedBase!!,
                    connection = connection,
                    nodes = nodes,
                    vms = vms,
                    storage = storage,
                    loading = loading,
                    vmLoading = vmLoading,
                    onVmAction = { vm, action ->
                        vmLoading = true
                        scope.launch {
                            try {
                                when (action) {
                                    VmAction.START -> api.startVm(connectedBase!!, connection, vm)
                                    VmAction.STOP -> api.stopVm(connectedBase!!, connection, vm)
                                    VmAction.REBOOT -> api.rebootVm(connectedBase!!, connection, vm)
                                    VmAction.SHUTDOWN -> api.shutdownVm(connectedBase!!, connection, vm)
                                    VmAction.RESET -> api.resetVm(connectedBase!!, connection, vm)
                                }
                                vms = api.getVms(connectedBase!!, connection)
                                storage = api.getStorage(connectedBase!!, connection)
                                clusterStatus = api.getClusterStatus(connectedBase!!, connection)
                                error = null
                            } catch (e: Exception) {
                                error = e.message ?: "Radnja nije uspjela."
                            } finally {
                                vmLoading = false
                            }
                        }
                    },
                    onRefresh = {
                        loading = true
                        scope.launch {
                            try {
                                nodes = api.getNodes(connectedBase!!, connection)
                                vms = api.getVms(connectedBase!!, connection)
                                storage = api.getStorage(connectedBase!!, connection)
                                error = null
                            } catch (e: Exception) {
                                error = e.message ?: "Greška."
                            } finally {
                                loading = false
                            }
                        }
                    },
                    onSettings = { showSettings = true },
                    onClusterManager = { showClusterManager = true },
                    onReplication = { showReplication = true; replicationLoading = true; scope.launch { try { replicationJobs = api.getReplicationJobs(connectedBase!!, connection) } catch (e: Exception) { error = e.message ?: "Replication se ne može učitati." } finally { replicationLoading = false } } },
                    onNodeOpen = { selectedNode = it },
                    onConsole = { consoleVm = it },
                    onSnapshot = { vm, snapName, description ->
                        vmLoading = true
                        scope.launch {
                            try {
                                api.createSnapshot(connectedBase!!, connection, vm, snapName, description)
                                vms = api.getVms(connectedBase!!, connection)
                                error = null
                            } catch (e: Exception) {
                                error = e.message ?: "Snapshot nije uspio."
                            } finally {
                                vmLoading = false
                            }
                        }
                    },
                    onBackup = { vm ->
                        vmLoading = true
                        scope.launch {
                            try { api.backupVm(connectedBase!!, connection, vm); vms = api.getVms(connectedBase!!, connection); error = null }
                            catch (e: Exception) { error = e.message ?: "Backup nije uspio." }
                            finally { vmLoading = false }
                        }
                    },
                    onUpdateAll = {
                        if (!updatingAll) {
                            updatingAll = true
                            updateStatuses = nodes.associate { it.node to "Čeka" }
                            scope.launch {
                                try {
                                    for (node in nodes) {
                                        updateStatuses = updateStatuses + (node.node to "Ažuriranje...")
                                        val result = console.upgradeNode(connectedBase!!, connection, node) { _ -> }
                                        updateStatuses = updateStatuses + (node.node to if (result.isSuccess) "Gotovo" else "Greška")
                                    }
                                } finally {
                                    updatingAll = false
                                }
                            }
                        }
                    },
                    updateAllBusy = updatingAll,
                    updateStatuses = updateStatuses,
                    error = error,
                    clusterStatus = clusterStatus
                )
                if (showUiSettings) { UiSettingsDialog(theme, language, { theme = it; uiPrefs.edit().putString("theme", it).apply() }, { language = it; uiPrefs.edit().putString("language", it).apply() }, { showUiSettings = false }) }
                if (selectedNode != null) { NodeManagementDialog(base = connectedBase!!, connection = connection, node = selectedNode!!, onShell = { shellNode = selectedNode }, onDismiss = { selectedNode = null }) }
                if (shellNode != null) { NodeShellScreen(base = connectedBase!!, connection = connection, node = shellNode!!, onBack = { shellNode = null }) }
                if (showReplication) { ReplicationDialog(jobs = replicationJobs, loading = replicationLoading, onRefresh = { replicationLoading = true; scope.launch { try { replicationJobs = api.getReplicationJobs(connectedBase!!, connection) } catch (e: Exception) { error = e.message ?: "Replication se ne može učitati." } finally { replicationLoading = false } } }, onDismiss = { showReplication = false }) }
                if (showClusterManager) {
                    ClusterManagementDialog(
                        base = connectedBase!!,
                        connection = connection,
                        clusterStatus = clusterStatus,
                        onDismiss = { showClusterManager = false },
                        onChanged = {
                            scope.launch {
                                try { clusterStatus = api.getClusterStatus(connectedBase!!, connection) }
                                catch (_: Exception) { }
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UiSettingsDialog(theme: String, language: String, onTheme: (String) -> Unit, onLanguage: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Izgled aplikacije") },
        text = {
            Column {
                Text("Tema")
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = theme == key, onClick = { onTheme(key) })
                        Text(label)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Jezik")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = language == "hr", onClick = { onLanguage("hr") })
                    Text("Hrvatski")
                    Spacer(Modifier.width(8.dp))
                    RadioButton(selected = language == "en", onClick = { onLanguage("en") })
                    Text("English")
                }
                Spacer(Modifier.height(4.dp))
                Text(if (language == "hr") "Jezik će se primijeniti na aplikacijski UI." else "Language will be applied to the app UI.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zatvori") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionScreen(
    initial: ProxmoxConnection,
    loading: Boolean,
    error: String?,
    profiles: List<ProxmoxServerProfile>,
    selectedProfile: String,
    onThemeSettings: () -> Unit = {},
    onConnect: (String, ProxmoxConnection) -> Unit
) {
    var profileName by remember { mutableStateOf(selectedProfile) }
    var profileMenu by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf(initial.localUrl.removePrefix("https://").removePrefix("http://").substringBefore(":8006")) }
    var localPort by remember { mutableStateOf("8006") }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var remote by remember { mutableStateOf(initial.remoteUrl) }
    var tokenId by remember { mutableStateOf(initial.tokenId) }
    var secret by remember { mutableStateOf(initial.tokenSecret) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Proxmox Mobile Manager") }, actions = { IconButton(onClick = onThemeSettings) { Icon(Icons.Default.Palette, null) } }) },
        bottomBar = {
            Surface(
                modifier = Modifier.navigationBarsPadding(),
                shadowElevation = 8.dp
            ) {
                Button(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    enabled = !loading && (local.isNotBlank() || remote.isNotBlank()),
                    onClick = {
                        val endpoint = if (local.isBlank()) "" else "https://" + local.trim().removePrefix("https://").removePrefix("http://").trimEnd('/') + ":" + localPort.ifBlank { "8006" }
                        onConnect(profileName.ifBlank { "Proxmox" }, ProxmoxConnection(endpoint, remote.trim().trimEnd('/'), username.trim(), password, tokenId.trim(), secret.trim()))
                    }
                ) {
                    if (loading) CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    else {
                        Icon(Icons.Default.Login, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Log in / Poveži se")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.Top
        ) {
            Text("Poveži Proxmox", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            ExposedDropdownMenuBox(expanded = profileMenu, onExpandedChange = { profileMenu = !profileMenu }) {
                OutlinedTextField(profileName, {}, Modifier.fillMaxWidth().menuAnchor(), label = { Text("Profil servera") }, readOnly = true)
                ExposedDropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                    profiles.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { profileName = p.name; local = p.connection.localUrl.removePrefix("https://").removePrefix("http://").substringBefore(":8006"); username = p.connection.username; password = p.connection.password; remote = p.connection.remoteUrl; tokenId = p.connection.tokenId; secret = p.connection.tokenSecret; profileMenu = false }) }
                    DropdownMenuItem(text = { Text("+ Novi server") }, onClick = { profileName = "Proxmox ${profiles.size + 1}"; profileMenu = false })
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Spremi više Proxmox servera i brzo se prebacuj između njih.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text("Na lokalnoj mreži koristi IP, korisničko ime i lozinku. Za udaljeni pristup domenom koristi se API token.")
            Spacer(Modifier.height(20.dp))
            Text("Lokalna mreža", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(local, { local = it }, Modifier.fillMaxWidth(), label = { Text("IP adresa") }, placeholder = { Text("npr. 192.168.1.37") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(localPort, { localPort = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), label = { Text("Port") }, placeholder = { Text("8006") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Korisničko ime") }, placeholder = { Text("root@pam") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Lozinka") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            Spacer(Modifier.height(18.dp))
            Text("Udaljeni pristup", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(remote, { remote = it }, Modifier.fillMaxWidth(), label = { Text("Domena / javna IP") }, placeholder = { Text("https://proxmox.mojadomena.hr:8006") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(tokenId, { tokenId = it }, Modifier.fillMaxWidth(), label = { Text("API token ID") }, placeholder = { Text("root@pam!mobile") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth(), label = { Text("API token secret") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            Spacer(Modifier.height(18.dp))
            if (error != null) { Text(error, color = MaterialTheme.colorScheme.error); Spacer(Modifier.height(12.dp)) }

        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClusterManagementDialog(
    base: String,
    connection: ProxmoxConnection,
    clusterStatus: ProxmoxClusterStatus?,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("create") }
    var clusterName by remember { mutableStateOf("") }
    var hostname by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var link0 by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(if (mode == "create") "Kreiraj Proxmox cluster" else "Pridruži node postojećem clusteru") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == "create", onClick = { mode = "create" }, label = { Text("Kreiraj") })
                    FilterChip(selected = mode == "join", onClick = { mode = "join" }, label = { Text("Join") })
                }
                Spacer(Modifier.height(10.dp))
                if (mode == "create") {
                    OutlinedTextField(clusterName, { clusterName = it }, Modifier.fillMaxWidth(), label = { Text("Naziv clustera") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    Text("Kreiranje clustera mijenja Proxmox cluster konfiguraciju. Koristi ga samo na nodeu koji treba postati prvi član.", style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedTextField(hostname, { hostname = it }, Modifier.fillMaxWidth(), label = { Text("IP / hostname postojećeg clustera") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Lozinka root@pam na clusteru") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(fingerprint, { fingerprint = it }, Modifier.fillMaxWidth(), label = { Text("PVE SSL fingerprint") }, singleLine = false)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(link0, { link0 = it }, Modifier.fillMaxWidth(), label = { Text("Link0 (opcionalno)") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    Text("VAŽNO: node koji pridružuješ clusteru ne smije sadržavati postojeće goste koje želiš zadržati. Join može promijeniti /etc/pve konfiguraciju i prekinuti trenutnu sesiju.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(message!!, color = if (message!!.startsWith("Uspješno")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !loading && if (mode == "create") clusterName.isNotBlank() else hostname.isNotBlank() && password.isNotBlank() && fingerprint.isNotBlank(),
                onClick = { confirm = true }
            ) { Text(if (loading) "Radim..." else if (mode == "create") "Kreiraj" else "Pridruži") }
        },
        dismissButton = { TextButton(enabled = !loading, onClick = onDismiss) { Text("Odustani") } }
    )

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Potvrdi opasnu operaciju") },
            text = { Text(if (mode == "create") "Kreiranje clustera je trajna administratorska operacija. Nastaviti?" else "Join može promijeniti cluster konfiguraciju i prekinuti rad ciljnog nodea. Potvrdi samo ako je ovaj node spreman za pridruživanje.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    loading = true
                    message = null
                    scope.launch {
                        try {
                            if (mode == "create") {
                                api.createCluster(base, connection, clusterName)
                            } else {
                                api.joinCluster(base, connection, hostname, password, fingerprint, link0)
                            }
                            message = "Uspješno. Osvježavam cluster status..."
                            onChanged()
                        } catch (e: Exception) {
                            message = e.message ?: "Cluster operacija nije uspjela."
                        } finally {
                            loading = false
                        }
                    }
                }) { Text("DA, nastavi") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Odustani") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dashboard(
    base: String,
    connection: ProxmoxConnection,
    nodes: List<ProxmoxNode>,
    vms: List<ProxmoxVm>,
    storage: List<ProxmoxStorage>,
    loading: Boolean,
    vmLoading: Boolean,
    onVmAction: (ProxmoxVm, VmAction) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onConsole: (ProxmoxVm) -> Unit,
    onSnapshot: (ProxmoxVm, String, String) -> Unit,
    onBackup: (ProxmoxVm) -> Unit,
    onUpdateAll: () -> Unit,
    updateAllBusy: Boolean,
    updateStatuses: Map<String, String>,
    error: String?,
    clusterStatus: ProxmoxClusterStatus?,
    onClusterManager: () -> Unit,
    onReplication: () -> Unit,
    onNodeOpen: (ProxmoxNode) -> Unit
) {
    val context = LocalContext.current
    val updater = remember { UpdateManager(context) }
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateInstalling by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var detailsVm by remember { mutableStateOf<ProxmoxVm?>(null) }
    var detailsConfig by remember { mutableStateOf<ProxmoxVmConfig?>(null) }
    var detailsLoading by remember { mutableStateOf(false) }
    var detailsError by remember { mutableStateOf<String?>(null) }
    var editVm by remember { mutableStateOf<ProxmoxVm?>(null) }
    var snapshotsVm by remember { mutableStateOf<ProxmoxVm?>(null) }
    var snapshots by remember { mutableStateOf<List<Pair<String,String>>>(emptyList()) }
    var snapshotsLoading by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var tasksVm by remember { mutableStateOf<ProxmoxVm?>(null) }
    var firewallVm by remember { mutableStateOf<ProxmoxVm?>(null) }
    var firewallRules by remember { mutableStateOf<List<ProxmoxFirewallRule>>(emptyList()) }
    var firewallLoading by remember { mutableStateOf(false) }
    var cloneVmState by remember { mutableStateOf<ProxmoxVm?>(null) }
    var cloneId by remember { mutableStateOf("") }
    var cloneName by remember { mutableStateOf("") }
    var cloneFull by remember { mutableStateOf(true) }
    var cloneLoading by remember { mutableStateOf(false) }
    var migrateVmState by remember { mutableStateOf<ProxmoxVm?>(null) }
    var migrateTarget by remember { mutableStateOf("") }
    var migrateOnline by remember { mutableStateOf(true) }
    var migrateLoading by remember { mutableStateOf(false) }
    var backupVmState by remember { mutableStateOf<ProxmoxVm?>(null) }
    var backupStorage by remember { mutableStateOf("") }
    var backupLoading by remember { mutableStateOf(false) }
    var tasks by remember { mutableStateOf<List<ProxmoxTask>>(emptyList()) }
    var tasksLoading by remember { mutableStateOf(false) }
    var nodeTasksNode by remember { mutableStateOf<String?>(null) }
    var nodeTasks by remember { mutableStateOf<List<ProxmoxTask>>(emptyList()) }
    var nodeTasksLoading by remember { mutableStateOf(false) }
    var resourceQuery by remember { mutableStateOf("") }
    var resourceStatusFilter by remember { mutableStateOf("all") }
    var resourceSort by remember { mutableStateOf("node") }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var storageQuery by remember { mutableStateOf("") }
    var storageStatusFilter by remember { mutableStateOf("all") }
    val filteredVms = vms.filter { vm ->
        val query = resourceQuery.trim()
        val matchesQuery = query.isBlank() || vm.name.contains(query, ignoreCase = true) || vm.vmid.toString().contains(query) || vm.node.contains(query, ignoreCase = true) || vm.type.contains(query, ignoreCase = true)
        val matchesStatus = when (resourceStatusFilter) {
            "running" -> vm.status.equals("running", ignoreCase = true)
            "stopped" -> !vm.status.equals("running", ignoreCase = true)
            else -> true
        }
        matchesQuery && matchesStatus
    }
    val orderedVms = when (resourceSort) {
        "name" -> filteredVms.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        "cpu" -> filteredVms.sortedByDescending { it.cpu }
        "memory" -> filteredVms.sortedByDescending { it.mem }
        "vmid" -> filteredVms.sortedBy { it.vmid }
        else -> filteredVms.sortedWith(compareBy({ it.node }, { it.vmid }))
    }
    val filteredStorage = storage.filter { item ->
        val query = storageQuery.trim()
        val matchesQuery = query.isBlank() ||
            item.storage.contains(query, ignoreCase = true) ||
            item.node.contains(query, ignoreCase = true) ||
            item.type.contains(query, ignoreCase = true) ||
            item.content.contains(query, ignoreCase = true)
        val matchesStatus = when (storageStatusFilter) {
            "active" -> item.active
            "inactive" -> !item.active
            else -> true
        }
        matchesQuery && matchesStatus
    }.sortedWith(compareBy<ProxmoxStorage> { it.storage.lowercase() }.thenBy { it.node.lowercase() })

    LaunchedEffect(Unit) {
        updateChecking = true
        try {
            update = updater.checkForUpdate()
        } catch (_: Exception) {
            updateMessage = "Provjera ažuriranja nije uspjela."
        } finally {
            updateChecking = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("Proxmox Manager", style = MaterialTheme.typography.titleLarge)
                        Text(base, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !loading && !vmLoading) { Icon(Icons.Default.Refresh, contentDescription = "Osvježi") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Postavke") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("PREGLED SUSTAVA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.weight(1f))
                        if (loading || vmLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Osvježavanje", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF10181D))
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    modifier = Modifier.size(46.dp),
                                    shape = MaterialTheme.shapes.large,
                                    color = androidx.compose.ui.graphics.Color(0xFF17343B)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Proxmox VE", style = MaterialTheme.typography.titleLarge)
                                    Text("Povezani sustav", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = androidx.compose.ui.graphics.Color(0xFF123A2B)
                                ) {
                                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(7.dp).background(androidx.compose.ui.graphics.Color(0xFF35D07F), CircleShape))
                                        Spacer(Modifier.width(6.dp))
                                        Text("API", style = MaterialTheme.typography.labelMedium, color = androidx.compose.ui.graphics.Color(0xFF65E6A2))
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Surface(Modifier.weight(1f), color = androidx.compose.ui.graphics.Color(0xFF17252B), shape = MaterialTheme.shapes.large) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Dns, contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFF35D07F), modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(5.dp))
                                            Text("NODEOVI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text("${nodes.count { it.status.equals("online", true) }} / ${nodes.size}", style = MaterialTheme.typography.headlineMedium, color = androidx.compose.ui.graphics.Color(0xFF35D07F))
                                        Text("online", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Surface(Modifier.weight(1f), color = androidx.compose.ui.graphics.Color(0xFF17252B), shape = MaterialTheme.shapes.large) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(5.dp))
                                            Text("VM / LXC", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text("${vms.count { it.status.equals("running", true) }} / ${vms.size}", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                                        Text("pokrenuto / ukupno", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            val cpuUsage = if (nodes.isEmpty()) 0f else nodes.filter { it.status.equals("online", true) }.map { (it.cpu * 100.0).toFloat() }.average().toFloat().coerceIn(0f, 100f)
                            val totalMem = nodes.filter { it.status.equals("online", true) }.sumOf { it.mem }
                            val maxMem = nodes.filter { it.status.equals("online", true) }.sumOf { it.maxMem }
                            val memUsage = if (maxMem > 0L) (totalMem.toDouble() / maxMem.toDouble() * 100.0).toFloat().coerceIn(0f, 100f) else 0f
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) { Text("CPU", style = MaterialTheme.typography.titleSmall); Spacer(Modifier.weight(1f)); Text("${cpuUsage.toInt()}%", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium) }
                                    LinearProgressIndicator(progress = { cpuUsage / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp), color = MaterialTheme.colorScheme.primary, trackColor = androidx.compose.ui.graphics.Color(0xFF293940))
                                }
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) { Text("RAM", style = MaterialTheme.typography.titleSmall); Spacer(Modifier.weight(1f)); Text("${memUsage.toInt()}%", color = androidx.compose.ui.graphics.Color(0xFF35D07F), style = MaterialTheme.typography.titleMedium) }
                                    LinearProgressIndicator(progress = { memUsage / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp), color = androidx.compose.ui.graphics.Color(0xFF35D07F), trackColor = androidx.compose.ui.graphics.Color(0xFF293940))
                                    Text("${formatBytes(totalMem)} / ${formatBytes(maxMem)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (update != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SystemUpdate, contentDescription = null)
                                Spacer(Modifier.padding(horizontal = 6.dp))
                                Text("Nova verzija ${update!!.versionName}", style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Nova verzija je dostupna. APK se preuzima iz GitHub Releasea.")
                            Spacer(Modifier.height(12.dp))
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !updateInstalling,
                                onClick = {
                                    if (!updater.canInstallUnknownApps()) {
                                        updateMessage = "Dozvoli instalaciju ažuriranja za ovu aplikaciju, pa ponovno pritisni Ažuriraj."
                                        updater.openInstallPermissionSettings()
                                    } else {
                                        updateInstalling = true
                                        updateMessage = null
                                        scope.launch {
                                            try {
                                                updater.downloadAndInstall(update!!)
                                            } catch (e: Exception) {
                                                updateMessage = e.message ?: "Ažuriranje nije uspjelo."
                                            } finally {
                                                updateInstalling = false
                                            }
                                        }
                                    }
                                }
                            ) {
                                if (updateInstalling) CircularProgressIndicator(modifier = Modifier.height(20.dp))
                                else Text("Ažuriraj aplikaciju")
                            }
                            if (updateMessage != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(updateMessage!!, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            } else if (!updateChecking) {
                item { Text("Aplikacija je ažurna.", style = MaterialTheme.typography.bodySmall) }
            }


            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Proxmox serveri", style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Ažurira sve nodeove jedan po jedan preko službene Proxmox nadogradnje.")
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onUpdateAll, enabled = !updateAllBusy && nodes.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            if (updateAllBusy) CircularProgressIndicator(modifier = Modifier.height(20.dp))
                            else Icon(Icons.Default.SystemUpdate, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (updateAllBusy) "Ažuriranje u tijeku..." else "Update svi serveri")
                        }
                        updateStatuses.forEach { (node, status) ->
                            Text(node + ": " + status, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Hub, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Proxmox Cluster", style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.height(6.dp))
                        if (clusterStatus?.clustered == true) {
                            Text("Cluster: ${clusterStatus.name}")
                            Text("Quorum: ${if (clusterStatus.quorate) "OK" else "NEMA QUORUMA"}")
                            if (clusterStatus.version.isNotBlank()) Text("Verzija: ${clusterStatus.version}")
                            Text("Nodeovi: ${clusterStatus.nodes.size}")
                            clusterStatus.nodes.forEach { (name, online) ->
                                Text("$name • ${if (online == "1") "online" else "offline"}", style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            Text("Ovaj Proxmox trenutno nije član clustera.")
                            Text("U sljedećem koraku dodajemo kreiranje i siguran join clustera.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Replication", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(6.dp)); Text("Pregled Proxmox replication jobova između nodeova."); Spacer(Modifier.height(10.dp)); Button(onClick = onReplication, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Sync, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Otvori Replication") } } } }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("RESURSI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Virtualne mašine i LXC", style = MaterialTheme.typography.headlineSmall)
                    }
                    Surface(shape = CircleShape, color = androidx.compose.ui.graphics.Color(0xFF17343B)) {
                        Text("${vms.size}", modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = resourceQuery,
                    onValueChange = { resourceQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Traži VM/LXC") },
                    placeholder = { Text("Naziv, VMID ili node") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = { if (resourceQuery.isNotEmpty()) IconButton(onClick = { resourceQuery = "" }) { Icon(Icons.Default.Close, contentDescription = "Očisti pretragu") } }
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = resourceStatusFilter == "all", onClick = { resourceStatusFilter = "all" }, label = { Text("Sve (${vms.size})") })
                    FilterChip(selected = resourceStatusFilter == "running", onClick = { resourceStatusFilter = "running" }, label = { Text("Pokrenute") })
                    FilterChip(selected = resourceStatusFilter == "stopped", onClick = { resourceStatusFilter = "stopped" }, label = { Text("Zaustavljene") })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sortiranje", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    Box {
                        TextButton(onClick = { sortMenuExpanded = true }) {
                            Text(when (resourceSort) { "name" -> "Naziv"; "cpu" -> "Najveći CPU"; "memory" -> "Najviše RAM-a"; "vmid" -> "VMID"; else -> "Node / VMID" })
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                            listOf("node" to "Node / VMID", "name" to "Naziv", "vmid" to "VMID", "cpu" to "Najveći CPU", "memory" to "Najviše RAM-a").forEach { (value, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { resourceSort = value; sortMenuExpanded = false })
                            }
                        }
                    }
                }
                if (filteredVms.size != vms.size) Text("Prikazano ${filteredVms.size} od ${vms.size}", style = MaterialTheme.typography.bodySmall)
            }
            if (vmLoading && vms.isEmpty()) {
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
            }
            items(orderedVms, key = { it.node + "-" + it.type + "-" + it.vmid }) { vm ->
                VmCard(vm, vmLoading, onVmAction, onConsole, onSnapshot, onEditConfig = { selected ->
                    editVm = selected
                }, onSnapshots = { selected ->
                    snapshotsVm = selected
                    snapshots = emptyList()
                    snapshotsLoading = true
                    scope.launch {
                        try { snapshots = api.getSnapshots(base, connection, selected) }
                        catch (e: Exception) { actionMessage = e.message ?: "Snapshoti se ne mogu učitati." }
                        finally { snapshotsLoading = false }
                    }
                }, onBackup = { selected ->
                    backupVmState = selected
                    backupStorage = ""
                }, onFirewall = { selected ->
                    firewallVm = selected
                    firewallRules = emptyList()
                    firewallLoading = true
                    scope.launch {
                        try { firewallRules = api.getFirewallRules(base, connection, selected) }
                        catch (e: Exception) { actionMessage = e.message ?: "Firewall pravila se ne mogu učitati." }
                        finally { firewallLoading = false }
                    }
                }, onClone = { selected ->
                    cloneVmState = selected
                    if (selected.isQemu) {
                        cloneId = ""
                        cloneName = selected.name + "-clone"
                        cloneFull = true
                    } else {
                        cloneVmState = null
                        actionMessage = "Clone je trenutno omogućen samo za QEMU VM."
                    }
                }, onMigrate = { selected ->
                    migrateVmState = selected
                    migrateTarget = nodes.firstOrNull { it.node != selected.node }?.node.orEmpty()
                    migrateOnline = true
                }, onTasks = { selected ->
                    tasksVm = selected
                    tasks = emptyList()
                    tasksLoading = true
                    scope.launch {
                        try { tasks = api.getTasks(base, connection, selected) }
                        catch (e: Exception) { actionMessage = e.message ?: "Povijest zadataka se ne može učitati." }
                        finally { tasksLoading = false }
                    }
                }, onDetails = { selected ->
                    detailsVm = selected
                    detailsConfig = null
                    detailsError = null
                    detailsLoading = true
                    scope.launch {
                        try {
                            detailsConfig = api.getVmConfig(base, connection, selected)
                        } catch (e: Exception) {
                            detailsError = e.message ?: "Konfiguraciju nije moguće učitati."
                        } finally {
                            detailsLoading = false
                        }
                    }
                })
            }
            if (!vmLoading && vms.isEmpty()) item { Text("Nema pronađenih VM/LXC resursa.") }
            else if (!vmLoading && filteredVms.isEmpty()) item { Text("Nema VM/LXC resursa koji odgovaraju pretrazi i odabranom filtru.") }

            if (loading) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            items(nodes) { node ->
                val nodeCpu = (node.cpu * 100f).coerceIn(0f, 100f)
                val nodeMem = if (node.maxMem > 0L) (node.mem.toDouble() / node.maxMem.toDouble() * 100.0).toFloat().coerceIn(0f, 100f) else 0f
                val nodeOnline = node.status.equals("online", ignoreCase = true)
                val uptimeDays = node.uptime / 86400L
                val uptimeHours = (node.uptime % 86400L) / 3600L
                val uptimeMinutes = (node.uptime % 3600L) / 60L
                Card(
                    Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF10181D))
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(42.dp),
                                shape = MaterialTheme.shapes.large,
                                color = if (nodeOnline) androidx.compose.ui.graphics.Color(0xFF123A2B) else androidx.compose.ui.graphics.Color(0xFF3A2424)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Dns, contentDescription = null, tint = if (nodeOnline) androidx.compose.ui.graphics.Color(0xFF65E6A2) else MaterialTheme.colorScheme.error)
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(node.node, style = MaterialTheme.typography.titleMedium)
                                Text("Uptime: ${uptimeDays}d ${uptimeHours}h ${uptimeMinutes}m", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Surface(
                                shape = CircleShape,
                                color = if (nodeOnline) androidx.compose.ui.graphics.Color(0xFF123A2B) else androidx.compose.ui.graphics.Color(0xFF3A2424)
                            ) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(7.dp).background(if (nodeOnline) androidx.compose.ui.graphics.Color(0xFF35D07F) else MaterialTheme.colorScheme.error, CircleShape))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (nodeOnline) "ONLINE" else node.status.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (nodeOnline) androidx.compose.ui.graphics.Color(0xFF65E6A2) else MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("CPU", style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.weight(1f))
                                Text("${"%.1f".format(nodeCpu)}%  •  ${node.maxCpu} vCPU", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                            LinearProgressIndicator(progress = { nodeCpu / 100f }, modifier = Modifier.fillMaxWidth().height(7.dp), color = MaterialTheme.colorScheme.primary, trackColor = androidx.compose.ui.graphics.Color(0xFF293940))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("RAM", style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.weight(1f))
                                Text("${nodeMem.toInt()}%", style = MaterialTheme.typography.labelLarge, color = androidx.compose.ui.graphics.Color(0xFF35D07F))
                            }
                            LinearProgressIndicator(progress = { nodeMem / 100f }, modifier = Modifier.fillMaxWidth().height(7.dp), color = androidx.compose.ui.graphics.Color(0xFF35D07F), trackColor = androidx.compose.ui.graphics.Color(0xFF293940))
                            Text("${formatBytes(node.mem)} / ${formatBytes(node.maxMem)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { onNodeOpen(node) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Dns, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Upravljaj serverom")
                        }
                        OutlinedButton(
                            onClick = {
                                nodeTasksNode = node.node
                                nodeTasks = emptyList()
                                nodeTasksLoading = true
                                scope.launch {
                                    try { nodeTasks = api.getNodeTasks(base, connection, node.node) }
                                    catch (e: Exception) { actionMessage = e.message ?: "Povijest zadataka se ne može učitati." }
                                    finally { nodeTasksLoading = false }
                                }
                            },
                            enabled = !nodeTasksLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.History, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (nodeTasksLoading && nodeTasksNode == node.node) "Učitavam zadatke..." else "Povijest zadataka")
                        }
                    }
                }
            }
            if (!loading && nodes.isEmpty()) item { Text("Nema pronađenih nodeova.") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("POHRANA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Storage", style = MaterialTheme.typography.headlineSmall)
                }
                OutlinedTextField(
                    value = storageQuery,
                    onValueChange = { storageQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Traži storage, node ili tip") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (storageQuery.isNotBlank()) {
                            IconButton(onClick = { storageQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Očisti pretragu")
                            }
                        }
                    },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = storageStatusFilter == "all",
                        onClick = { storageStatusFilter = "all" },
                        label = { Text("Svi") }
                    )
                    FilterChip(
                        selected = storageStatusFilter == "active",
                        onClick = { storageStatusFilter = "active" },
                        label = { Text("Aktivni") }
                    )
                    FilterChip(
                        selected = storageStatusFilter == "inactive",
                        onClick = { storageStatusFilter = "inactive" },
                        label = { Text("Neaktivni") }
                    )
                }
                Text("Prikazano: ${filteredStorage.size} / ${storage.size}", style = MaterialTheme.typography.bodySmall)
            }
            if (filteredStorage.isEmpty()) {
                item {
                    Text(
                        if (storage.isEmpty()) "Nema dostupnih storage zapisa." else "Nema storage zapisa koji odgovaraju pretrazi ili filteru.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            items(filteredStorage, key = { it.node + "-" + it.storage }) { s ->
                val usedPercent = if (s.total > 0L) ((s.used.toDouble() / s.total.toDouble()) * 100.0).toInt().coerceIn(0, 100) else 0
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Storage, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(s.storage, style = MaterialTheme.typography.titleMedium)
                        }
                        Text("${s.node} • ${s.type} • ${if (s.active) "aktivno" else "neaktivno"}")
                        Text("Iskorišteno: ${formatBytes(s.used)} / ${formatBytes(s.total)} ($usedPercent%)")
                        LinearProgressIndicator(
                            progress = { usedPercent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Slobodno: ${formatBytes(s.avail)}")
                        if (s.content.isNotBlank()) Text("Sadržaj: ${s.content}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

    
    if (nodeTasksNode != null) {
        val nodeName = nodeTasksNode!!
        AlertDialog(
            onDismissRequest = { if (!nodeTasksLoading) nodeTasksNode = null },
            title = { Text("$" + "{nodeName} • Povijest zadataka") },
            text = {
                when {
                    nodeTasksLoading -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                    nodeTasks.isEmpty() -> Text("Nema zadataka za ovaj node.")
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                        items(nodeTasks, key = { it.upid }) { task ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                                Text(task.type.ifBlank { "task" }, style = MaterialTheme.typography.titleSmall)
                                Text("Status: " + task.status.ifBlank { "—" })
                                if (task.exitStatus.isNotBlank()) Text("Rezultat: " + task.exitStatus)
                                if (task.user.isNotBlank()) Text("Korisnik: " + task.user, style = MaterialTheme.typography.bodySmall)
                            }
                            Divider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { nodeTasksNode = null }, enabled = !nodeTasksLoading) { Text("Zatvori") } }
        )
    }

    if (firewallVm != null) {
        val vm = firewallVm!!
        AlertDialog(
            onDismissRequest = { firewallVm = null },
            title = { Text("${vm.name} • Firewall") },
            text = {
                when {
                    firewallLoading -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                    firewallRules.isEmpty() -> Text("Nema firewall pravila ili ih Proxmox nije vratio.")
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                        items(firewallRules, key = { it.pos }) { rule ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                                Text("${rule.pos}. ${rule.action.uppercase()} • ${rule.type.ifBlank { "rule" }}", style = MaterialTheme.typography.titleSmall)
                                if (rule.comment.isNotBlank()) Text(rule.comment)
                                val details = listOf("Interface" to rule.iface, "Source" to rule.source, "Destination" to rule.dest, "Protocol" to rule.proto, "DPort" to rule.dport, "SPort" to rule.sport).filter { it.second.isNotBlank() }
                                details.forEach { (k,v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall) }
                                Text(if (rule.enable) "Enabled" else "Disabled", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            try {
                                                api.setFirewallRuleEnabled(base, connection, vm, rule.pos, !rule.enable)
                                                firewallRules = api.getFirewallRules(base, connection, vm)
                                                actionMessage = if (rule.enable) "Firewall pravilo ${rule.pos} isključeno." else "Firewall pravilo ${rule.pos} uključeno."
                                            } catch (e: Exception) { actionMessage = e.message ?: "Promjena firewall pravila nije uspjela." }
                                        }
                                    }) { Text(if (rule.enable) "Isključi" else "Uključi") }
                                    TextButton(onClick = {
                                        scope.launch {
                                            try {
                                                api.deleteFirewallRule(base, connection, vm, rule.pos)
                                                firewallRules = api.getFirewallRules(base, connection, vm)
                                                actionMessage = "Firewall pravilo ${rule.pos} obrisano."
                                            } catch (e: Exception) { actionMessage = e.message ?: "Brisanje firewall pravila nije uspjelo." }
                                        }
                                    }) { Text("Obriši") }
                                }
                            }
                            Divider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { firewallVm = null }) { Text("Zatvori") } }
        )
    }
    if (tasksVm != null) {
        val vm = tasksVm!!
        AlertDialog(
            onDismissRequest = { tasksVm = null },
            title = { Text("${vm.name} • Povijest zadataka") },
            text = {
                when {
                    tasksLoading -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                    tasks.isEmpty() -> Text("Nema zabilježenih zadataka.")
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                        items(tasks, key = { it.upid }) { task ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                                Text(task.type.ifBlank { "task" }, style = MaterialTheme.typography.titleSmall)
                                Text("Status: ${task.status.ifBlank { "—" }}")
                                if (task.exitStatus.isNotBlank()) Text("Rezultat: ${task.exitStatus}")
                                if (task.user.isNotBlank()) Text("Korisnik: ${task.user}", style = MaterialTheme.typography.bodySmall)
                            }
                            Divider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { tasksVm = null }) { Text("Zatvori") } }
        )
    }

    if (editVm != null) {
        val vm = editVm!!
        var config by remember(vm.vmid, vm.node) { mutableStateOf<Map<String,String>>(emptyMap()) }
        var saving by remember { mutableStateOf(false) }
        LaunchedEffect(vm.vmid, vm.node) {
            try { config = api.getVmConfig(base, connection, vm).entries.toMap() }
            catch (e: Exception) { actionMessage = e.message ?: "Konfiguraciju nije moguće učitati." }
        }
        AlertDialog(
            onDismissRequest = { if (!saving) editVm = null },
            title = { Text("${vm.name} • Uredi konfiguraciju") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    val editable = listOf("name","cores","sockets","memory","balloon","onboot","boot","cpuunits","ostype","net0")
                    items(editable) { key ->
                        val value = config[key].orEmpty()
                        OutlinedTextField(value, { newValue -> config = config + (key to newValue) },
                            modifier=Modifier.fillMaxWidth().padding(vertical=3.dp),
                            label={Text(key)}, singleLine=true)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled=!saving, onClick={
                    saving=true
                    scope.launch {
                        try {
                            val editable=listOf("name","cores","sockets","memory","balloon","onboot","boot","cpuunits","ostype","net0")
                            for(key in editable) if(config.containsKey(key)) api.updateVmConfig(base,connection,vm,key,config[key].orEmpty())
                            actionMessage="Konfiguracija spremljena."
                            editVm=null
                        } catch(e:Exception) { actionMessage=e.message ?: "Spremanje nije uspjelo." }
                        finally { saving=false }
                    }
                }) { Text(if(saving) "Spremanje..." else "Spremi") }
            },
            dismissButton={ TextButton(enabled=!saving,onClick={editVm=null}){Text("Odustani")} }
        )
    }

    if (snapshotsVm != null) {
        val vm=snapshotsVm!!
        AlertDialog(
            onDismissRequest={snapshotsVm=null},
            title={Text("${vm.name} • Snapshoti")},
            text={
                if(snapshotsLoading) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){CircularProgressIndicator()}
                else if(snapshots.isEmpty()) Text("Nema snapshot-a.")
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max=420.dp)){
                    items(snapshots,key={it.first}){ snap ->
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1f)){Text(snap.first); if(snap.second.isNotBlank())Text(snap.second,style=MaterialTheme.typography.bodySmall)}
                            TextButton(onClick={
                                scope.launch { try { api.rollbackSnapshot(base,connection,vm,snap.first); snapshots=api.getSnapshots(base,connection,vm); actionMessage="Snapshot vraćen." } catch(e:Exception){actionMessage=e.message ?: "Rollback nije uspio."} }
                            }){Text("Vrati")}
                            TextButton(onClick={
                                scope.launch { try { api.deleteSnapshot(base,connection,vm,snap.first); snapshots=api.getSnapshots(base,connection,vm); actionMessage="Snapshot obrisan." } catch(e:Exception){actionMessage=e.message ?: "Brisanje nije uspjelo."} }
                            }){Text("Obriši")}
                        }
                        Divider()
                    }
                }
            },
            confirmButton={TextButton(onClick={snapshotsVm=null}){Text("Zatvori")}}
        )
    }

    if (migrateVmState != null) {
        val vm = migrateVmState!!
        AlertDialog(onDismissRequest={if(!migrateLoading)migrateVmState=null},title={Text("${vm.name} • Migracija")},text={Column{Text("Izvor: ${vm.node}");nodes.filter{it.node!=vm.node}.forEach{node->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){RadioButton(selected=migrateTarget==node.node,onClick={migrateTarget=node.node},enabled=!migrateLoading);Text(node.node)}};Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked=migrateOnline,onCheckedChange={migrateOnline=it},enabled=!migrateLoading);Text("Online migracija")}}},confirmButton={TextButton(enabled=!migrateLoading&&migrateTarget.isNotBlank(),onClick={migrateLoading=true;scope.launch{try{api.migrateVm(base,connection,vm,migrateTarget,migrateOnline);onRefresh();actionMessage="Migracija završena.";migrateVmState=null}catch(e:Exception){actionMessage=e.message?:"Migracija nije uspjela."}finally{migrateLoading=false}}}){Text(if(migrateLoading)"Migriranje..." else "Migriraj")}},dismissButton={TextButton(enabled=!migrateLoading,onClick={migrateVmState=null}){Text("Odustani")}})}

    if (backupVmState != null) {
        val vm = backupVmState!!
        val backupStorages = storage.filter { s ->
            s.node == vm.node && s.active && s.enabled && s.content.split(',').map { it.trim().lowercase() }.contains("backup")
        }
        AlertDialog(
            onDismissRequest = { if (!backupLoading) backupVmState = null },
            title = { Text("${vm.name} • Backup") },
            text = {
                Column {
                    Text("Odredišni storage", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = backupStorage.isBlank(), onClick = { backupStorage = "" }, enabled = !backupLoading)
                        Column { Text("Automatski"); Text("Proxmox bira zadani backup storage.", style = MaterialTheme.typography.bodySmall) }
                    }
                    backupStorages.forEach { s ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = backupStorage == s.storage, onClick = { backupStorage = s.storage }, enabled = !backupLoading)
                            Column { Text(s.storage); Text("${s.type} • slobodno ${formatBytes(s.avail)}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    if (backupStorages.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Nije pronađen posebno označen backup storage na nodeu. Možeš ipak koristiti Automatski.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !backupLoading, onClick = {
                    backupLoading = true
                    scope.launch {
                        try {
                            api.backupVm(base, connection, vm, backupStorage.ifBlank { null })
                            onRefresh()
                            actionMessage = if (backupStorage.isBlank()) "Backup ${vm.name} je uspješno završen." else "Backup ${vm.name} je uspješno spremljen na ${backupStorage}."
                            backupVmState = null
                        } catch (e: Exception) {
                            actionMessage = e.message ?: "Backup nije uspio."
                        } finally { backupLoading = false }
                    }
                }) { Text(if (backupLoading) "Backup u tijeku..." else "Pokreni backup") }
            },
            dismissButton = { TextButton(enabled = !backupLoading, onClick = { backupVmState = null }) { Text("Odustani") } }
        )
    }

    if (cloneVmState != null) {
        val vm = cloneVmState!!
        AlertDialog(
            onDismissRequest = { if (!cloneLoading) cloneVmState = null },
            title = { Text("${vm.name} • Kloniraj VM") },
            text = {
                Column {
                    OutlinedTextField(value = cloneId, onValueChange = { cloneId = it.filter(Char::isDigit).take(6) }, label = { Text("Novi VMID") }, placeholder = { Text("npr. 120") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = cloneName, onValueChange = { cloneName = it.take(80) }, label = { Text("Naziv klona") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = cloneFull, onCheckedChange = { cloneFull = it }, enabled = !cloneLoading)
                        Text("Full clone (kopira diskove)")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !cloneLoading && cloneId.toIntOrNull() != null && cloneName.isNotBlank(), onClick = {
                    val newId = cloneId.toIntOrNull() ?: return@TextButton
                    cloneLoading = true
                    scope.launch {
                        try {
                            api.cloneVm(base, connection, vm, newId, cloneName.trim(), cloneFull)
                            onRefresh()
                            actionMessage = "VM ${vm.name} je kloniran kao ${cloneName.trim()} (VMID $newId)."
                            cloneVmState = null
                        } catch (e: Exception) { actionMessage = e.message ?: "Kloniranje nije uspjelo." }
                        finally { cloneLoading = false }
                    }
                }) { Text(if (cloneLoading) "Kloniranje..." else "Kloniraj") }
            },
            dismissButton = { TextButton(enabled = !cloneLoading, onClick = { cloneVmState = null }) { Text("Odustani") } }
        )
    }

    if (actionMessage != null) {
        AlertDialog(onDismissRequest={actionMessage=null},title={Text("Proxmox")},text={Text(actionMessage!!)},confirmButton={TextButton(onClick={actionMessage=null}){Text("OK")}})
    }

    if (detailsVm != null) {
            val vm = detailsVm!!
            AlertDialog(
                onDismissRequest = { if (!detailsLoading) detailsVm = null },
                title = { Text("${vm.name} • Konfiguracija") },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        Text("${vm.type.uppercase()} • VMID ${vm.vmid} • ${vm.node}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        when {
                            detailsLoading -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                            detailsError != null -> Text(detailsError!!, color = MaterialTheme.colorScheme.error)
                            detailsConfig?.entries?.isEmpty() == true -> Text("Proxmox nije vratio konfiguracijske stavke.")
                            detailsConfig != null -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                                items(detailsConfig!!.entries, key = { it.first }) { entry ->
                                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                        Text(entry.first, style = MaterialTheme.typography.labelMedium)
                                        Text(entry.second.ifBlank { "—" })
                                    }
                                    Divider()
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { detailsVm = null }, enabled = !detailsLoading) { Text("Zatvori") }
                }
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplicationDialog(jobs: List<ProxmoxReplicationJob>, loading: Boolean, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!loading) onDismiss() }, title = { Text("Replication jobovi") }, text = {
        Column(Modifier.fillMaxWidth()) {
            if (loading) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            else if (jobs.isEmpty()) Text("Nema pronađenih replication jobova.")
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                items(jobs, key = { it.id.ifBlank { it.target + it.type } }) { job ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Column(Modifier.padding(12.dp)) {
                        Text("Job: ${job.id.ifBlank { "—" }}", style = MaterialTheme.typography.titleMedium)
                        Text("Target: ${job.target.ifBlank { "—" }}")
                        Text("Tip: ${job.type.ifBlank { "—" }}")
                        Text("Raspored: ${job.schedule.ifBlank { "—" }}")
                        Text("Status: ${job.state.ifBlank { "—" }}")
                        if (job.lastSync > 0) Text("Zadnja sinkronizacija: ${job.lastSync}")
                        if (job.duration > 0) Text("Trajanje: ${job.duration}s")
                        if (job.failCount > 0) Text("Neuspjeli pokušaji: ${job.failCount}")
                        if (job.error.isNotBlank()) Text("Greška: ${job.error}", color = MaterialTheme.colorScheme.error)
                    } }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onRefresh, enabled = !loading) { Text("Osvježi") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !loading) { Text("Zatvori") } })
}

@Composable
private fun NodeManagementDialog(base: String, connection: ProxmoxConnection, node: ProxmoxNode, onShell: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope(); var tab by remember { mutableStateOf("summary") }; var logs by remember { mutableStateOf<List<String>>(emptyList()) }; var updates by remember { mutableStateOf<List<Pair<String,String>>>(emptyList()) }; var disks by remember { mutableStateOf<List<String>>(emptyList()) }; var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<String?>(null) }; var confirmNodeAction by remember { mutableStateOf<String?>(null) }
    fun loadLogs() { busy=true; scope.launch { try { logs=api.getNodeSyslog(base,connection,node); message=null } catch(e:Exception){message=e.message} finally{busy=false} } }
    fun loadUpdates() { busy=true; scope.launch { try { updates=api.getNodeAptUpdates(base,connection,node); message=null } catch(e:Exception){message=e.message} finally{busy=false} } }
    fun loadDisks() { busy=true; scope.launch { try { disks=api.getNodeDisks(base,connection,node); message=null } catch(e:Exception){message=e.message} finally{busy=false} } }
    LaunchedEffect(tab) { if(tab=="logs") loadLogs(); if(tab=="updates") loadUpdates(); if(tab=="disks") loadDisks() }
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text("Server: ${node.node}")},text={Column(Modifier.fillMaxWidth()){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("summary" to "Summary","logs" to "Syslog","updates" to "Updates","disks" to "Diskovi").forEach{(id,label)->OutlinedButton(onClick={tab=id},enabled=!busy,modifier=Modifier.weight(1f)){Text(label)}}};Spacer(Modifier.height(8.dp));when(tab){"summary"->Column{Text("Status: ${node.status}");Text("CPU: ${"%.1f".format(node.cpu*100)}% / ${node.maxCpu} CPU");Text("RAM: ${formatBytes(node.mem)} / ${formatBytes(node.maxMem)}");Text("Uptime: ${node.uptime}s");Spacer(Modifier.height(8.dp));Text("Node management");Spacer(Modifier.height(8.dp));Button(onClick=onShell,enabled=!busy,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Terminal,null);Spacer(Modifier.width(4.dp));Text("Otvori Shell")};Spacer(Modifier.height(8.dp));OutlinedButton(onClick={confirmNodeAction="reboot"},enabled=!busy&&node.status=="online",modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.RestartAlt,null);Spacer(Modifier.width(4.dp));Text("Restartaj node")};OutlinedButton(onClick={confirmNodeAction="shutdown"},enabled=!busy&&node.status=="online",modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.PowerSettingsNew,null);Spacer(Modifier.width(4.dp));Text("Ugasi node")};Text("Restart/gašenje prekida rad svih VM-ova i LXC-ova na nodeu.");Spacer(Modifier.height(8.dp));Text("Syslog i Updates su sada dostupni; Disks, Firewall i System slijede kao zasebni moduli.")};"logs"->if(busy)CircularProgressIndicator() else LazyColumn(Modifier.heightIn(max=350.dp)){items(logs){Text(it,style=MaterialTheme.typography.bodySmall);Divider()}};"disks"->if(busy)CircularProgressIndicator() else if(disks.isEmpty()) Text("Nema podataka o diskovima ili Proxmox korisnik nema potrebne dozvole.") else LazyColumn(Modifier.heightIn(max=350.dp)){items(disks){Text(it,style=MaterialTheme.typography.bodySmall);Divider()}};else->if(busy)CircularProgressIndicator() else LazyColumn(Modifier.heightIn(max=350.dp)){items(updates){Text("${it.first}  ${it.second}")}}};if(message!=null)Text(message!!,color=MaterialTheme.colorScheme.error)}},confirmButton={TextButton(onClick=onDismiss,enabled=!busy){Text("Zatvori")}},dismissButton={TextButton(onClick={if(tab=="logs")loadLogs() else if(tab=="updates")loadUpdates() else if(tab=="disks")loadDisks()},enabled=!busy&&tab!="summary"){Text("Osvježi")}})
    if (confirmNodeAction != null) {
        val action = confirmNodeAction!!
        AlertDialog(
            onDismissRequest = { if (!busy) confirmNodeAction = null },
            title = { Text(if (action == "reboot") "Restartaj node ${node.node}?" else "Ugasi node ${node.node}?") },
            text = { Text(if (action == "reboot") "Restart će prekinuti rad svih VM-ova i LXC-ova na ovom nodeu." else "Gašenje će zaustaviti cijeli node i sve VM-ove/LXC-ove na njemu.") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                confirmNodeAction = null; busy = true; message = null
                scope.launch { try { api.controlNode(base, connection, node, action); message = if (action == "reboot") "Zahtjev za restart nodea poslan." else "Zahtjev za gašenje nodea poslan." } catch (e: Exception) { message = e.message ?: "Radnja nije uspjela." } finally { busy = false } }
            }) { Text(if (action == "reboot") "Potvrdi restart" else "Potvrdi gašenje") } },
            dismissButton = { TextButton(onClick = { confirmNodeAction = null }, enabled = !busy) { Text("Odustani") } }
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeShellScreen(base: String, connection: ProxmoxConnection, node: ProxmoxNode, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val stateKey = remember(base, node.node) { "node_shell_" + base.hashCode() + "_" + node.node }
    var output by remember(stateKey) { mutableStateOf("Spajanje na " + node.node + "...\n") }
    val latestOutput by rememberUpdatedState(output)
    var input by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun connect() {
        if (connecting) return
        connecting = true
        error = null
        scope.launch {
            try {
                console.openNode(base, connection, node, { b ->
                    val t = String(b, Charsets.UTF_8)
                    if (t == "OK") connected = true else output = (output + t).takeLast(50000)
                }, { reason ->
                    connected = false
                    if (reason != null) output = (output + "\n[Veza zatvorena: " + reason + "]\n").takeLast(50000)
                }, { e ->
                    connected = false
                    error = e.message ?: "Greška node shell-a"
                })
            } catch (e: Exception) {
                connected = false
                error = e.message ?: "Spajanje na node shell nije uspjelo."
            } finally {
                connecting = false
            }
        }
    }

    LaunchedEffect(node) { connect() }

    DisposableEffect(lifecycleOwner, node) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> if (!connected) connect()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> context.getSharedPreferences("console_state", Context.MODE_PRIVATE).edit().putString(stateKey, latestOutput.takeLast(30000)).apply()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.getSharedPreferences("console_state", Context.MODE_PRIVATE).edit().putString(stateKey, latestOutput.takeLast(30000)).apply()
            console.close()
        }
    }

    LaunchedEffect(node, connected) {
        if (connected) while (true) {
            delay(15000)
            if (connected) console.ping()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(node.node + " • Shell") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Natrag") } },
                actions = {
                    TextButton(enabled = output.isNotBlank(), onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(output)) }) { Text("Kopiraj") }
                    Text(if (connected) "● LIVE" else if (connecting) "○ spajanje" else "○ offline", Modifier.padding(end = 8.dp))
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(8.dp)) {
            Surface(Modifier.fillMaxWidth().weight(1f)) {
                SelectionContainer { Text(output, Modifier.fillMaxSize().padding(8.dp), style = MaterialTheme.typography.bodySmall) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ clipboard.setText(androidx.compose.ui.text.AnnotatedString(output)) }, enabled=output.isNotBlank(), modifier=Modifier.weight(1f)) { Text("Kopiraj sve") }
                OutlinedButton({ output="" }, modifier=Modifier.weight(1f)) { Text("Očisti") }
            }
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(input, { input=it }, Modifier.weight(1f), label={Text("Unos")}, singleLine=true, enabled=connected)
                Spacer(Modifier.width(6.dp))
                Button({ console.send(input+"\n"); input="" }, enabled=connected && input.isNotEmpty()) { Text("Pošalji") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                TextButton({ console.send("\u0003") }, enabled=connected) { Text("Ctrl+C") }
                TextButton({ console.send("\u0004") }, enabled=connected) { Text("Ctrl+D") }
                TextButton({ console.send("\t") }, enabled=connected) { Text("Tab") }
                TextButton({ console.send("\u001b[A") }, enabled=connected) { Text("↑") }
                TextButton({ console.send("\u001b[B") }, enabled=connected) { Text("↓") }
            }
        }
    }
}

@Composable
private fun VmCard(
    vm: ProxmoxVm,
    busy: Boolean,
    onAction: (ProxmoxVm, VmAction) -> Unit,
    onConsole: (ProxmoxVm) -> Unit,
    onSnapshot: (ProxmoxVm, String, String) -> Unit,
    onBackup: (ProxmoxVm) -> Unit,
    onEditConfig: (ProxmoxVm) -> Unit,
    onSnapshots: (ProxmoxVm) -> Unit,
    onTasks: (ProxmoxVm) -> Unit,
    onFirewall: (ProxmoxVm) -> Unit,
    onClone: (ProxmoxVm) -> Unit,
    onMigrate: (ProxmoxVm) -> Unit,
    onDetails: (ProxmoxVm) -> Unit
) {
    var confirmAction by remember { mutableStateOf<VmAction?>(null) }
    var showSnapshot by remember { mutableStateOf(false) }
    var snapshotName by remember { mutableStateOf("") }
    var snapshotDescription by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(vm.name, style = MaterialTheme.typography.titleLarge)
                    Text(vm.type.uppercase() + " • VMID " + vm.vmid + " • " + vm.node)
                }
                Text(vm.status)
            }
            Spacer(Modifier.height(8.dp))
            Text("RAM: " + formatBytes(vm.mem) + " / " + formatBytes(vm.maxMem))
            Text("CPU: " + "%.1f".format(vm.cpu * 100) + "%")
            if (vm.maxDisk > 0) Text("Disk: " + formatBytes(vm.maxDisk))
            Spacer(Modifier.height(12.dp))
            OutlinedButton({ onConsole(vm) }, enabled=!busy, modifier=Modifier.fillMaxWidth()){ Icon(Icons.Default.Terminal,null); Spacer(Modifier.width(4.dp)); Text("Console") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ onDetails(vm) }, enabled=!busy, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Tune, null); Spacer(Modifier.width(4.dp)); Text("Detalji / konfiguracija") }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ onEditConfig(vm) }, enabled=!busy, modifier=Modifier.weight(1f)) { Text("Uredi") }
                OutlinedButton({ onSnapshots(vm) }, enabled=!busy, modifier=Modifier.weight(1f)) { Text("Snapshoti") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ onBackup(vm) }, enabled=!busy, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Backup, null); Spacer(Modifier.width(4.dp)); Text("Backup") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ onFirewall(vm) }, enabled=!busy, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Security, null); Spacer(Modifier.width(4.dp)); Text("Firewall") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ onClone(vm) }, enabled=!busy && vm.isQemu, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(4.dp)); Text("Kloniraj QEMU VM") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ onMigrate(vm) }, enabled=!busy, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.SwapHoriz, null); Spacer(Modifier.width(4.dp)); Text("Migriraj na drugi node") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                { showSnapshot = true; snapshotName = ""; snapshotDescription = "" },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CameraAlt, null)
                Spacer(Modifier.width(4.dp))
                Text("Napravi snapshot")
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.isRunning) {
                    OutlinedButton({ confirmAction = VmAction.REBOOT }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.RestartAlt, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Restart")
                    }
                    OutlinedButton({ confirmAction = VmAction.SHUTDOWN }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PowerSettingsNew, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Shutdown")
                    }
                    OutlinedButton({ confirmAction = VmAction.STOP }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Stop, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Stop")
                    }
                } else {
                    Button({ onAction(vm, VmAction.START) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Start")
                    }
                }
            }
            if (vm.isRunning && vm.isQemu) {
                OutlinedButton({ confirmAction = VmAction.RESET }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Reset (QEMU)")
                }
            }
        }
    }
    if (showSnapshot) {
        AlertDialog(
            onDismissRequest = { if (!busy) showSnapshot = false },
            title = { Text("Snapshot ${vm.name}") },
            text = {
                Column {
                    OutlinedTextField(
                        value = snapshotName,
                        onValueChange = { snapshotName = it.replace(" ", "-").take(80) },
                        label = { Text("Naziv snapshot-a") },
                        placeholder = { Text("npr. prije-updatea") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = snapshotDescription,
                        onValueChange = { snapshotDescription = it.take(200) },
                        label = { Text("Opis (nije obavezno)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = snapshotName.isNotBlank() && !busy,
                    onClick = {
                        val name = snapshotName.trim()
                        val description = snapshotDescription.trim()
                        showSnapshot = false
                        onSnapshot(vm, name, description)
                    }
                ) { Text("Kreiraj") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { showSnapshot = false }) { Text("Odustani") }
            }
        )
    }

    confirmAction?.let { action ->
        val stop = action == VmAction.STOP
        val shutdown = action == VmAction.SHUTDOWN
        val reset = action == VmAction.RESET
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(when { stop -> "Zaustavi ${vm.name}?"; shutdown -> "Shutdown ${vm.name}?"; reset -> "Reset ${vm.name}?"; else -> "Restartaj ${vm.name}?" }) },
            text = { Text("Ova radnja će se poslati Proxmox serveru.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmAction = null
                    onAction(vm, action)
                }) { Text(when { stop -> "Zaustavi"; shutdown -> "Shutdown"; reset -> "Reset"; else -> "Restartaj" }) }
            },
            dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("Odustani") } }
        )
    }
}

private enum class VmAction { START, STOP, REBOOT, SHUTDOWN, RESET }

private fun formatBytes(value: Long): String {
    if (value <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = value.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "${"%.1f".format(v)} ${units[i]}"
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleScreen(base:String,connection:ProxmoxConnection,vm:ProxmoxVm,onBack:()->Unit){
 val scope=rememberCoroutineScope()
 val lifecycleOwner=androidx.lifecycle.compose.LocalLifecycleOwner.current
 val clipboard=androidx.compose.ui.platform.LocalClipboardManager.current
 val context=androidx.compose.ui.platform.LocalContext.current
 val focusManager=androidx.compose.ui.platform.LocalFocusManager.current
 val consolePrefs=remember{context.getSharedPreferences("console_state", Context.MODE_PRIVATE)}
 val stateKey=remember(base,vm.node,vm.vmid){"console_"+base.hashCode()+"_"+vm.node+"_"+vm.vmid}
 var output by remember(stateKey){mutableStateOf(consolePrefs.getString(stateKey,"Spajanje na Proxmox konzolu...\n")?:"Spajanje na Proxmox konzolu...\n")}
 val latestOutput by rememberUpdatedState(output)
 var input by remember{mutableStateOf("")}
 var connected by remember{mutableStateOf(false)}
 var connecting by remember{mutableStateOf(false)}
 var error by remember{mutableStateOf<String?>(null)}
 val terminalBg=androidx.compose.ui.graphics.Color(0xFF020506)
 val terminalFg=androidx.compose.ui.graphics.Color(0xFFD6F4E1)

 fun sendCommand(){
  if(!connected || input.isEmpty()) return
  console.send(input+"\r")
  input=""
  focusManager.clearFocus()
 }

 fun connect(){
  if(connecting) return
  connecting=true
  error=null
  scope.launch{
   try{
    console.open(base,connection,vm,{b->
     val t=String(b,Charsets.UTF_8)
     if(t=="OK") connected=true else output=(output+t).takeLast(50000)
    },{reason->
     connected=false
     if(reason!=null) output=(output+"\n[Veza zatvorena: "+reason+"]\n").takeLast(50000)
    },{e->
     connected=false
     error=e.message?:"Greška konzole"
    })
   }catch(e:Exception){
    connected=false
    error=e.message?:"Spajanje na konzolu nije uspjelo."
   }finally{
    connecting=false
   }
  }
 }

 LaunchedEffect(vm){ connect() }

 androidx.compose.runtime.DisposableEffect(lifecycleOwner,vm){
  val observer=androidx.lifecycle.LifecycleEventObserver{_,event->
   when(event){
    androidx.lifecycle.Lifecycle.Event.ON_RESUME -> if(!connected) connect()
    androidx.lifecycle.Lifecycle.Event.ON_STOP -> consolePrefs.edit().putString(stateKey,latestOutput.takeLast(30000)).apply()
    else -> Unit
   }
  }
  lifecycleOwner.lifecycle.addObserver(observer)
  onDispose{
   lifecycleOwner.lifecycle.removeObserver(observer)
   consolePrefs.edit().putString(stateKey,latestOutput.takeLast(30000)).apply()
   console.close()
  }
 }

 LaunchedEffect(vm, connected){
  if (connected) {
   while (true) {
    delay(15000)
    if (connected) console.ping()
   }
  }
 }

 LaunchedEffect(vm){
  var retryDelay = 1000L
  while (true) {
   delay(retryDelay)
   if (!connected && !connecting) {
    connect()
    retryDelay = (retryDelay * 2).coerceAtMost(15000L)
   } else if (connected) {
    retryDelay = 1000L
   }
  }
 }

 Scaffold(
  topBar={TopAppBar(
   title={Text("Console · "+vm.name)},
   navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Natrag")}},
   actions={
    TextButton(enabled=output.isNotBlank(),onClick={clipboard.setText(androidx.compose.ui.text.AnnotatedString(output))}){Text("Kopiraj")}
    Text(if(connected)"● LIVE" else if(connecting)"○ spajanje" else "○ offline",Modifier.padding(end=8.dp))
   }
  )}
 ){p->
  Column(Modifier.fillMaxSize().padding(p).padding(horizontal=8.dp, vertical=6.dp), verticalArrangement=Arrangement.spacedBy(6.dp)){
   Surface(Modifier.fillMaxWidth().weight(1f), color=terminalBg, shape=MaterialTheme.shapes.medium){
    Column(Modifier.fillMaxSize().padding(10.dp)){
     SelectionContainer(Modifier.weight(1f).fillMaxWidth()){
      Text(output,Modifier.fillMaxWidth(),color=terminalFg,style=MaterialTheme.typography.bodySmall.copy(fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace,lineHeight=14.sp))
     }
     Row(Modifier.fillMaxWidth().padding(top=6.dp),verticalAlignment=Alignment.CenterVertically){
      Text("›",color=androidx.compose.ui.graphics.Color(0xFF35D07F),style=MaterialTheme.typography.bodyMedium.copy(fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace))
      Spacer(Modifier.width(8.dp))
      androidx.compose.foundation.text.BasicTextField(
       value=input,
       onValueChange={input=it},
       modifier=Modifier.weight(1f).fillMaxWidth(),
       enabled=connected,
       singleLine=true,
       textStyle=MaterialTheme.typography.bodySmall.copy(color=terminalFg,fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace),
       cursorBrush=androidx.compose.ui.graphics.SolidColor(terminalFg),
       keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Send, capitalization=androidx.compose.ui.text.input.KeyboardCapitalization.None),
       keyboardActions=androidx.compose.foundation.text.KeyboardActions(onSend={sendCommand()}),
       decorationBox={innerTextField->
        Box(Modifier.fillMaxWidth()){
         if(input.isEmpty()) Text(if(connected)"Upiši ili zalijepi naredbu…" else "Čekam vezu s konzolom…",color=terminalFg.copy(alpha=0.55f),style=MaterialTheme.typography.bodySmall.copy(fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace))
         innerTextField()
        }
       }
      )
     }
    }
   }
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
    OutlinedButton({clipboard.setText(androidx.compose.ui.text.AnnotatedString(output))},enabled=output.isNotBlank(),modifier=Modifier.weight(1f)){Text("Kopiraj sve")}
    OutlinedButton({output="";error=null},modifier=Modifier.weight(1f)){Text("Očisti")}
   }
   if(error!=null)Text(error!!,color=MaterialTheme.colorScheme.error)
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
    TextButton({console.send("\u0003")},enabled=connected){Text("Ctrl+C")}
    TextButton({console.send("\u0004")},enabled=connected){Text("Ctrl+D")}
    TextButton({console.send("\t")},enabled=connected){Text("Tab")}
    TextButton({console.send("\u001b[A")},enabled=connected){Text("↑")}
    TextButton({console.send("\u001b[B")},enabled=connected){Text("↓")}
   }
  }
 }
}
