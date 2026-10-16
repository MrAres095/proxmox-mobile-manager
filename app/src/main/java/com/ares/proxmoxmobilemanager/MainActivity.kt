package com.ares.proxmoxmobilemanager

import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
    val dark = when (theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val appColors = if (dark) darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF38D6E8),
        onPrimary = androidx.compose.ui.graphics.Color(0xFF04171B),
        secondary = androidx.compose.ui.graphics.Color(0xFF65E6A2),
        background = androidx.compose.ui.graphics.Color(0xFF070B12),
        surface = androidx.compose.ui.graphics.Color(0xFF101722),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF182331),
        onSurface = androidx.compose.ui.graphics.Color(0xFFF0F6FC),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF9BAFC2),
        outline = androidx.compose.ui.graphics.Color(0xFF2B3B4D),
        outlineVariant = androidx.compose.ui.graphics.Color(0xFF223142)
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
            var loginUpdateBusy by remember { mutableStateOf(false) }
            var loginUpdateMessage by remember { mutableStateOf<String?>(null) }
            var updateStatuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("proxmox_connection", Context.MODE_PRIVATE) }
            var profiles by remember { mutableStateOf(ProxmoxServerProfiles.load(context)) }
            var selectedProfile by remember { mutableStateOf(profiles.firstOrNull()?.name ?: "Proxmox") }
            var connection by remember { mutableStateOf(ProxmoxConnection(prefs.getString("localUrl", "") ?: "", prefs.getString("remoteUrl", "") ?: "", prefs.getString("username", "") ?: "", prefs.getString("password", "") ?: "", prefs.getString("tokenId", "") ?: "", prefs.getString("tokenSecret", "") ?: "")) }
            val scope = rememberCoroutineScope()

            if (consoleVm != null && connectedBase != null) { ConsoleScreen(connectedBase!!, connection, consoleVm!!, { console.close(); consoleVm=null }) } else if (showSettings || connectedBase == null) {
                ConnectionScreen(
                    connection, loading, error, profiles, selectedProfile,
                    onThemeSettings = { showUiSettings = true },
                    onUpdate = {
                        scope.launch {
                            val updater = UpdateManager(context)
                            try {
                                loginUpdateBusy = true
                                loginUpdateMessage = "Provjeravam GitHub Release..."
                                val available = updater.checkForUpdate()
                                if (available == null) {
                                    loginUpdateMessage = "Aplikacija je ažurirana ili GitHub trenutno nije dostupan."
                                } else if (!updater.canInstallUnknownApps()) {
                                    loginUpdateMessage = "Dozvoli instalaciju za aplikaciju, pa ponovno pritisni Ažuriraj."
                                    updater.openInstallPermissionSettings()
                                } else {
                                    loginUpdateMessage = "Preuzimam verziju ${available.versionName}..."
                                    updater.downloadAndInstall(available)
                                    loginUpdateMessage = "APK je preuzet. Dovrši instalaciju u Android prozoru."
                                }
                            } catch (e: Exception) {
                                loginUpdateMessage = e.message ?: "Ažuriranje nije uspjelo."
                            } finally {
                                loginUpdateBusy = false
                            }
                        }
                    },
                    updateBusy = loginUpdateBusy,
                    updateMessage = loginUpdateMessage,
                    onSaveProfile = { profileName, newConnection ->
                        selectedProfile = profileName
                        connection = newConnection
                        ProxmoxServerProfiles.save(context, ProxmoxServerProfile(profileName, newConnection))
                        profiles = ProxmoxServerProfiles.load(context)
                        prefs.edit().putString("localUrl", newConnection.localUrl).putString("remoteUrl", newConnection.remoteUrl).putString("username", newConnection.username).putString("password", newConnection.password).putString("tokenId", newConnection.tokenId).putString("tokenSecret", newConnection.tokenSecret).apply()
                    },
                    onDeleteProfile = { profileName ->
                        ProxmoxServerProfiles.delete(context, profileName)
                        profiles = ProxmoxServerProfiles.load(context)
                        if (selectedProfile == profileName) selectedProfile = profiles.firstOrNull()?.name ?: "Novi server"
                    }
                ) { profileName, newConnection ->
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
    onUpdate: () -> Unit = {},
    updateBusy: Boolean = false,
    updateMessage: String? = null,
    onSaveProfile: (String, ProxmoxConnection) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onConnect: (String, ProxmoxConnection) -> Unit
) {
    var profileName by remember { mutableStateOf(selectedProfile) }
    var local by remember { mutableStateOf(android.net.Uri.parse(if (initial.localUrl.contains("://")) initial.localUrl else "https://" + initial.localUrl).host.orEmpty()) }
    var localPort by remember { mutableStateOf(android.net.Uri.parse(if (initial.localUrl.contains("://")) initial.localUrl else "https://" + initial.localUrl).port.takeIf { it > 0 }?.toString() ?: "8006") }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var remote by remember { mutableStateOf(initial.remoteUrl) }
    var tokenId by remember { mutableStateOf(initial.tokenId) }
    var secret by remember { mutableStateOf(initial.tokenSecret) }
    var remoteMode by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var showPassword by remember { mutableStateOf(false) }

    fun applyProfile(profile: ProxmoxServerProfile) {
        profileName = profile.name
        val parsed = android.net.Uri.parse(if (profile.connection.localUrl.contains("://")) profile.connection.localUrl else "https://" + profile.connection.localUrl)
        local = parsed.host.orEmpty()
        localPort = parsed.port.takeIf { it > 0 }?.toString() ?: "8006"
        username = profile.connection.username
        password = profile.connection.password
        remote = profile.connection.remoteUrl
        tokenId = profile.connection.tokenId
        secret = profile.connection.tokenSecret
        remoteMode = local.isBlank() && remote.isNotBlank()
    }

    fun buildConnection(): ProxmoxConnection {
        val host = local.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        val endpoint = if (host.isBlank()) "" else "https://$host:${localPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8006}"
        return ProxmoxConnection(endpoint, remote.trim().trimEnd('/'), username.trim(), password, tokenId.trim(), secret.trim())
    }

    val remoteUri = remember(remote) { android.net.Uri.parse(remote.trim()) }
    val remoteAddressValid = remoteUri.scheme.equals("https", ignoreCase = true) &&
        !remoteUri.host.isNullOrBlank() &&
        remoteUri.userInfo == null
    val localHost = local.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
    val localHostValid = localHost.isNotBlank() &&
        !localHost.any { it.isWhitespace() || it == '/' || it == '?' || it == '#' }
    val connectionInputValid = if (remoteMode) {
        remoteAddressValid
    } else {
        localHostValid && (localPort.toIntOrNull()?.let { it in 1..65535 } == true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
                            Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(9.dp).size(25.dp))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("Proxmox Mobile", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Text("SERVER MANAGER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.6.sp)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onUpdate, enabled = !loading && !updateBusy) {
                        if (updateBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.SystemUpdate, contentDescription = "Ažuriraj aplikaciju")
                    }
                    IconButton(onClick = onThemeSettings) { Icon(Icons.Default.Palette, contentDescription = "Izgled") }
                }
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp, modifier = Modifier.navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { if (profileName.isNotBlank() && (local.isNotBlank() || remote.isNotBlank())) onSaveProfile(profileName.trim(), buildConnection()) },
                        enabled = !loading && profileName.isNotBlank() && connectionInputValid,
                        modifier = Modifier.weight(0.9f).height(52.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Spremi")
                    }
                    Button(
                        onClick = { onConnect(profileName.ifBlank { "Proxmox" }.trim(), buildConnection()) },
                        enabled = !loading && connectionInputValid,
                        modifier = Modifier.weight(1.3f).height(52.dp)
                    ) {
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else {
                            Icon(Icons.Default.Login, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Poveži se")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (updateMessage != null) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null)
                        Text(updateMessage, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { /* message clears on next update attempt */ }) { Icon(Icons.Default.Info, contentDescription = null) }
                    }
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) {
                        Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(14.dp).size(34.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Svi tvoji Proxmox serveri", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text("Dodaj koliko god servera želiš. Svaki ima vlastitu adresu i podatke za prijavu.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${profiles.size} spremljeno", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("MOJI SERVERI", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.5.sp)
                    Text("Dodirni spremljeni server za uređivanje ili povezivanje.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = {
                    profileName = "Server ${profiles.size + 1}"
                    local = ""; localPort = "8006"; username = "root@pam"; password = ""
                    remote = ""; tokenId = ""; secret = ""; remoteMode = false
                }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Dodaj")
                }
            }

            if (profiles.isEmpty()) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Još nema spremljenih profila. Unesi podatke ispod i pritisni Spremi ili Poveži se.")
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    profiles.forEach { profile ->
                        val active = profile.name == profileName
                        Card(
                            modifier = Modifier.width(190.dp).clickable { applyProfile(profile) },
                            shape = MaterialTheme.shapes.large,
                            border = if (active) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface)
                        ) {
                            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Dns, contentDescription = null, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(profile.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                    Text(
                                        profile.connection.localUrl.ifBlank { profile.connection.remoteUrl }.removePrefix("https://").removePrefix("http://").take(24),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                IconButton(onClick = { deleteTarget = profile.name }, modifier = Modifier.size(34.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Ukloni ${profile.name}", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(9.dp).size(22.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Postavke veze", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Text("Naziv profila i pristupni podaci", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    OutlinedTextField(
                        value = profileName, onValueChange = { profileName = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Naziv servera") }, placeholder = { Text("npr. Kućni Proxmox") }, singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !remoteMode, onClick = { remoteMode = false }, label = { Text("Lokalna mreža") }, leadingIcon = { Icon(Icons.Default.Wifi, null) })
                        FilterChip(selected = remoteMode, onClick = { remoteMode = true }, label = { Text("Udaljeni pristup") }, leadingIcon = { Icon(Icons.Default.Public, null) })
                    }
                    if (!remoteMode) {
                        OutlinedTextField(
                            value = local, onValueChange = { local = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("IP adresa ili hostname") }, placeholder = { Text("192.168.1.37") }, singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Router, contentDescription = null) }
                        )
                        if (local.isNotBlank() && !localHostValid) {
                            Text("Unesi samo IP adresu ili naziv hosta, bez putanje ili razmaka.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (localPort.isNotBlank() && localPort.toIntOrNull()?.let { it < 1 || it > 65535 } == true) {
                            Text("Port mora biti broj između 1 i 65535.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                            OutlinedTextField(
                                value = localPort, onValueChange = { localPort = it.filter(Char::isDigit).take(5) },
                                modifier = Modifier.weight(0.65f), label = { Text("Port") }, placeholder = { Text("8006") }, singleLine = true
                            )
                            OutlinedTextField(
                                value = username, onValueChange = { username = it }, modifier = Modifier.weight(1.35f),
                                label = { Text("Korisničko ime") }, placeholder = { Text("root@pam") }, singleLine = true
                            )
                        }
                        OutlinedTextField(
                            value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Lozinka") }, singleLine = true,
                            visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Prikaži ili sakrij lozinku") } }
                        )
                        Text("Za lokalni pristup unesi IP adresu svog Proxmox noda, port (obično 8006), korisnika i lozinku.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        OutlinedTextField(
                            value = remote, onValueChange = { remote = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("HTTPS adresa / domena") }, placeholder = { Text("https://pve.mojadomena.hr:8006") }, singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) }
                        )
                        OutlinedTextField(value = tokenId, onValueChange = { tokenId = it }, modifier = Modifier.fillMaxWidth(), label = { Text("API token ID") }, placeholder = { Text("root@pam!mobile") }, singleLine = true)
                        OutlinedTextField(
                            value = secret, onValueChange = { secret = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("API token secret") }, singleLine = true,
                            visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Prikaži ili sakrij token") } }
                        )
                        Text("Za udaljeni pristup koristi HTTPS i API token s ograničenim dozvolama. Nemoj izlagati Proxmox sučelje javno bez odgovarajuće zaštite.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (remote.isNotBlank() && !remoteAddressValid) {
                            Text("Unesi valjanu HTTPS adresu, npr. https://pve.mojadomena.hr:8006. Korisnički podaci ne smiju biti dio URL-a.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (error != null) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Ukloniti spremljeni server?") },
            text = { Text("Profil \"${deleteTarget}\" bit će uklonjen samo iz aplikacije. Proxmox server i njegovi podaci neće biti obrisani.") },
            confirmButton = {
                TextButton(onClick = {
                    val name = deleteTarget!!
                    onDeleteProfile(name)
                    if (profileName == name) {
                        profileName = "Novi server"
                        local = ""; localPort = "8006"; username = "root@pam"; password = ""
                        remote = ""; tokenId = ""; secret = ""; remoteMode = false
                    }
                    deleteTarget = null
                }) { Text("Ukloni", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Odustani") } }
        )
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
    var createGuestOpen by remember { mutableStateOf(false) }
    var createGuestKind by remember { mutableStateOf("qemu") }
    var createGuestNode by remember { mutableStateOf(nodes.firstOrNull()?.node.orEmpty()) }
    var createGuestId by remember { mutableStateOf("") }
    var createGuestName by remember { mutableStateOf("") }
    var createGuestCores by remember { mutableStateOf("2") }
    var createGuestMemory by remember { mutableStateOf("2048") }
    var createGuestDisk by remember { mutableStateOf("32") }
    var createGuestStorage by remember { mutableStateOf(storage.firstOrNull { it.active && it.enabled }?.storage.orEmpty()) }
    var createGuestBridge by remember { mutableStateOf("vmbr0") }
    var createGuestTemplate by remember { mutableStateOf("") }
    var createGuestPassword by remember { mutableStateOf("") }
    var createGuestBusy by remember { mutableStateOf(false) }
    var deleteGuestVm by remember { mutableStateOf<ProxmoxVm?>(null) }
    var deleteGuestConfirmation by remember { mutableStateOf("") }
    var deleteGuestBusy by remember { mutableStateOf(false) }
    var selectedGuestVmid by remember { mutableStateOf<Int?>(null) }
    var detailsVm by remember { mutableStateOf<ProxmoxVm?>(null) }
    var detailsConfig by remember { mutableStateOf<ProxmoxVmConfig?>(null) }
    var detailsLoading by remember { mutableStateOf(false) }
    var detailsError by remember { mutableStateOf<String?>(null) }
    var detailsIps by remember { mutableStateOf<List<String>>(emptyList()) }
    var detailsIpLoading by remember { mutableStateOf(false) }
    var detailsIpMessage by remember { mutableStateOf<String?>(null) }
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
    var storageTypeFilter by remember { mutableStateOf("all") }
    var selectedTab by remember { mutableStateOf("overview") }
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
        val storageType = item.type.lowercase()
        val isZfs = storageType.contains("zfs")
        val isLocal = storageType in setOf("dir", "lvm", "lvmthin", "btrfs", "zfspool")
        val matchesType = when (storageTypeFilter) {
            "local" -> isLocal && !isZfs
            "zfs" -> isZfs
            else -> true
        }
        matchesQuery && matchesStatus && matchesType
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

    val apiStatusLabel = when {
        error != null -> "GREŠKA"
        loading || vmLoading -> "UČITAVANJE"
        else -> "API OK"
    }
    val apiStatusColor = when {
        error != null -> MaterialTheme.colorScheme.error
        loading || vmLoading -> MaterialTheme.colorScheme.tertiary
        else -> androidx.compose.ui.graphics.Color(0xFF35D07F)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("Proxmox Manager", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(base, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !loading && !vmLoading) { Icon(Icons.Default.Refresh, contentDescription = "Osvježi") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Postavke") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = selectedTab == "overview", onClick = { selectedTab = "overview" }, icon = { Icon(Icons.Default.Dashboard, null) }, label = { Text("Pregled") })
                NavigationBarItem(selected = selectedTab == "guests", onClick = { selectedTab = "guests" }, icon = { Icon(Icons.Default.Dns, null) }, label = { Text("Guestovi") })
                NavigationBarItem(selected = selectedTab == "storage", onClick = { selectedTab = "storage" }, icon = { Icon(Icons.Default.Storage, null) }, label = { Text("Pohrana") })
                NavigationBarItem(selected = selectedTab == "nodes", onClick = { selectedTab = "nodes" }, icon = { Icon(Icons.Default.Computer, null) }, label = { Text("Nodeovi") })
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (selectedTab == "overview") {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Triple("guests", "Guestovi", Icons.Default.Dns), Triple("nodes", "Nodeovi", Icons.Default.Computer), Triple("storage", "Pohrana", Icons.Default.Storage)).forEach { (target, label, icon) ->
                            FilledTonalButton(
                                onClick = { selectedTab = target },
                                modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("PREGLED SUSTAVA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.6.sp)
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
                        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF101722))
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
                                    color = apiStatusColor.copy(alpha = 0.14f)
                                ) {
                                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(7.dp).background(apiStatusColor, CircleShape))
                                        Spacer(Modifier.width(6.dp))
                                        Text(apiStatusLabel, style = MaterialTheme.typography.labelMedium, color = apiStatusColor)
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Surface(Modifier.weight(1f), color = androidx.compose.ui.graphics.Color(0xFF182331), shape = MaterialTheme.shapes.large) {
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
                            if (error != null) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = MaterialTheme.shapes.medium
                                ) {
                                    Row(
                                        Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                        Text(error, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = onRefresh, enabled = !loading && !vmLoading) {
                                            Text("Pokušaj ponovno")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("INFRASTRUKTURA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.4.sp)
                        Text("Nodeovi", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    }
                    TextButton(onClick = { selectedTab = "nodes" }) {
                        Text("Svi nodeovi")
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                }
                if (nodes.isEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Text("Nema učitanih nodeova. Osvježi pregled ili provjeri vezu sa serverom.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        nodes.forEach { nodeItem ->
                            val online = nodeItem.status.equals("online", true)
                            val ramPercent = if (nodeItem.maxMem > 0L) (nodeItem.mem.toDouble() / nodeItem.maxMem.toDouble() * 100.0).toInt().coerceIn(0, 100) else 0
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { onNodeOpen(nodeItem) },
                                shape = MaterialTheme.shapes.large,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            modifier = Modifier.size(40.dp),
                                            shape = MaterialTheme.shapes.medium,
                                            color = if (online) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.Computer, contentDescription = null, tint = if (online) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer)
                                            }
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(nodeItem.node, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                            Text(if (online) "Dostupan" else "Nedostupan", style = MaterialTheme.typography.bodySmall, color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                        }
                                        Surface(shape = CircleShape, color = if (online) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.error.copy(alpha = 0.12f)) {
                                            Text(if (online) "ONLINE" else "OFFLINE", Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                        }
                                        Spacer(Modifier.width(4.dp))
                                        Icon(Icons.Default.ChevronRight, contentDescription = "Otvori node", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("CPU", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Spacer(Modifier.weight(1f))
                                                Text("${(nodeItem.cpu * 100.0).toInt().coerceIn(0, 100)}%", style = MaterialTheme.typography.labelLarge)
                                            }
                                            LinearProgressIndicator(progress = { (nodeItem.cpu * 100.0).toFloat().coerceIn(0f, 100f) / 100f }, modifier = Modifier.fillMaxWidth().height(5.dp))
                                        }
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("RAM", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Spacer(Modifier.weight(1f))
                                                Text("$ramPercent%", style = MaterialTheme.typography.labelLarge)
                                            }
                                            LinearProgressIndicator(progress = { ramPercent / 100f }, modifier = Modifier.fillMaxWidth().height(5.dp), color = MaterialTheme.colorScheme.secondary)
                                        }
                                    }
                                    Text("${formatBytes(nodeItem.mem)} / ${formatBytes(nodeItem.maxMem)} RAM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
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
                                val targetNode = nodes.firstOrNull { it.node == name }
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { targetNode?.let(onNodeOpen) },
                                    enabled = targetNode != null,
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(name, style = MaterialTheme.typography.titleSmall)
                                            Text(if (online == "1") "Online • dodirni za upravljanje" else "Offline • dodirni za detalje", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Icon(Icons.Default.ChevronRight, contentDescription = "Otvori node")
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Odaberi node iz clustera za pristup njegovim diskovima, mreži, zadacima i shellu.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("Ovaj Proxmox trenutno nije član clustera.")
                            Text("U sljedećem koraku dodajemo kreiranje i siguran join clustera.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Replication", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(6.dp)); Text("Pregled Proxmox replication jobova između nodeova."); Spacer(Modifier.height(10.dp)); Button(onClick = onReplication, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Sync, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Otvori Replication") } } } }
            }

            if (selectedTab == "guests") {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("GUESTOVI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Guestovi", style = MaterialTheme.typography.headlineSmall)
                    }
                    Surface(shape = CircleShape, color = androidx.compose.ui.graphics.Color(0xFF17343B)) {
                        Text("${vms.size}", modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                    }
                    IconButton(
                        onClick = {
                            createGuestNode = nodes.firstOrNull { it.status.equals("online", true) }?.node ?: nodes.firstOrNull()?.node.orEmpty()
                            createGuestStorage = storage.firstOrNull { it.active && it.enabled && it.node == createGuestNode && (it.content.contains("images") || it.content.contains("rootdir")) }?.storage
                                ?: storage.firstOrNull { it.active && it.enabled }?.storage.orEmpty()
                            createGuestOpen = true
                        },
                        enabled = nodes.any { it.status.equals("online", true) } && storage.any { it.active && it.enabled }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Kreiraj novi VM ili LXC")
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = resourceQuery,
                    onValueChange = { resourceQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Traži guestove") },
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
            orderedVms.groupBy { it.node }.forEach { (nodeName, nodeVms) ->
                item(key = "resource-node-$nodeName") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(nodeName, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text("${nodeVms.size} resursa", modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                items(nodeVms, key = { it.node + "-" + it.type + "-" + it.vmid }) { vm ->
                if (selectedGuestVmid == vm.vmid) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { selectedGuestVmid = null }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.ArrowBack, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Natrag na Guestove")
                        }
                        VmCard(vm, vmLoading, onVmAction, onConsole, onSnapshot, onDelete = { selected -> deleteGuestVm = selected; deleteGuestConfirmation = "" }, onEditConfig = { selected ->
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
                    detailsIps = emptyList()
                    detailsIpMessage = null
                    detailsIpLoading = true
                    detailsLoading = true
                    scope.launch {
                        try {
                            detailsConfig = api.getVmConfig(base, connection, selected)
                        } catch (e: Exception) {
                            detailsError = e.message ?: "Konfiguraciju nije moguće učitati."
                        } finally {
                            detailsLoading = false
                        }
                        try {
                            detailsIps = api.getGuestIpAddresses(base, connection, selected)
                            detailsIpMessage = if (!selected.isRunning) "Guest je zaustavljen." else if (detailsIps.isEmpty()) {
                                if (selected.isQemu) "Nema IP adresa. Provjeri je li QEMU Guest Agent instaliran i omogućen." else "Proxmox nije vratio IP adrese za ovaj LXC."
                            } else null
                        } catch (e: Exception) {
                            detailsIpMessage = if (!selected.isRunning) "Guest je zaustavljen." else if (selected.isQemu) {
                                "IP adrese nisu dostupne. Za VM treba biti instaliran i uključen QEMU Guest Agent."
                            } else {
                                "IP adrese ovog LXC-a trenutno nije moguće dohvatiti."
                            }
                        } finally {
                            detailsIpLoading = false
                        }
                    }
                })
                    }
                } else {
                    GuestListRow(vm = vm, onClick = { selectedGuestVmid = vm.vmid })
                }
                }
            }
            if (!vmLoading && vms.isEmpty()) item { Text("Nema pronađenih guestova (VM/LXC).") }
            else if (!vmLoading && filteredVms.isEmpty()) item { Text("Nijedan guest ne odgovara pretrazi i odabranom filtru.") }

            }

            if (selectedTab == "nodes") {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("INFRASTRUKTURA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.5.sp)
                                Text("Proxmox nodeovi", style = MaterialTheme.typography.headlineSmall)
                                Text("Stanje, opterećenje i administracija svakog čvora.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
                                Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp).size(26.dp))
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("ONLINE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${nodes.count { it.status.equals("online", true) }}", style = MaterialTheme.typography.headlineMedium, color = androidx.compose.ui.graphics.Color(0xFF35D07F))
                                }
                            }
                            Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("OFFLINE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${nodes.count { !it.status.equals("online", true) }}", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("UKUPNO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${nodes.size}", style = MaterialTheme.typography.headlineMedium)
                                }
                            }
                        }
                    }
                }
            }
            if (loading) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            items(nodes) { node ->
                val nodeCpu = (node.cpu * 100.0).toFloat().coerceIn(0f, 100f)
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
            }

            if (selectedTab == "storage") {
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
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = storageTypeFilter == "all", onClick = { storageTypeFilter = "all" }, label = { Text("Svi diskovi (${storage.size})") })
                    FilterChip(selected = storageTypeFilter == "local", onClick = { storageTypeFilter = "local" }, label = { Text("Lokalni") })
                    FilterChip(selected = storageTypeFilter == "zfs", onClick = { storageTypeFilter = "zfs" }, label = { Text("ZFS") })
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = storageStatusFilter == "all", onClick = { storageStatusFilter = "all" }, label = { Text("Svi statusi") })
                    FilterChip(selected = storageStatusFilter == "active", onClick = { storageStatusFilter = "active" }, label = { Text("Aktivni") })
                    FilterChip(selected = storageStatusFilter == "inactive", onClick = { storageStatusFilter = "inactive" }, label = { Text("Neaktivni") })
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
                val storageColor = when {
                    usedPercent >= 90 -> MaterialTheme.colorScheme.error
                    usedPercent >= 75 -> androidx.compose.ui.graphics.Color(0xFFFFC857)
                    else -> androidx.compose.ui.graphics.Color(0xFF35D07F)
                }
                Card(
                    Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF10181D))
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(modifier = Modifier.size(38.dp), shape = MaterialTheme.shapes.medium, color = androidx.compose.ui.graphics.Color(0xFF17252B)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(s.storage, style = MaterialTheme.typography.titleMedium)
                                Text("${s.node} • ${s.type}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (s.enabled) "Omogućeno u Proxmoxu" else "Onemogućeno u Proxmoxu", style = MaterialTheme.typography.labelSmall, color = if (s.enabled) androidx.compose.ui.graphics.Color(0xFF65E6A2) else MaterialTheme.colorScheme.error)
                            }
                            Surface(shape = CircleShape, color = if (s.active) androidx.compose.ui.graphics.Color(0xFF123A2B) else androidx.compose.ui.graphics.Color(0xFF263139)) {
                                Text(if (s.active) "AKTIVNO" else "NEAKTIVNO", modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = if (s.active) androidx.compose.ui.graphics.Color(0xFF65E6A2) else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Zauzeće", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.weight(1f))
                            Text("$usedPercent%", style = MaterialTheme.typography.titleMedium, color = storageColor)
                        }
                        LinearProgressIndicator(
                            progress = { usedPercent / 100f },
                            modifier = Modifier.fillMaxWidth().height(7.dp),
                            color = storageColor,
                            trackColor = androidx.compose.ui.graphics.Color(0xFF293940)
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("ISKORIŠTENO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatBytes(s.used), style = MaterialTheme.typography.bodyMedium)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("UKUPNO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatBytes(s.total), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Text("Slobodno: ${formatBytes(s.avail)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (s.content.isNotBlank()) Text("Sadržaj: ${s.content}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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

    if (createGuestOpen) {
        AlertDialog(
            onDismissRequest = { if (!createGuestBusy) createGuestOpen = false },
            title = { Text("Kreiraj VM / LXC") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text("Vrsta resursa", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = createGuestKind == "qemu", onClick = { createGuestKind = "qemu" }, label = { Text("QEMU VM") }, enabled = !createGuestBusy)
                        FilterChip(selected = createGuestKind == "lxc", onClick = { createGuestKind = "lxc" }, label = { Text("LXC container") }, enabled = !createGuestBusy)
                    }
                    OutlinedTextField(value = createGuestNode, onValueChange = { createGuestNode = it }, label = { Text("Node") }, supportingText = { Text("Dostupni: " + nodes.filter { it.status.equals("online", true) }.joinToString { it.node }) }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !createGuestBusy)
                    OutlinedTextField(value = createGuestId, onValueChange = { createGuestId = it.filter(Char::isDigit).take(9) }, label = { Text("VMID") }, placeholder = { Text("npr. 120") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !createGuestBusy)
                    OutlinedTextField(value = createGuestName, onValueChange = { createGuestName = it.take(80) }, label = { Text(if (createGuestKind == "lxc") "Hostname" else "Naziv VM-a") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !createGuestBusy)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = createGuestCores, onValueChange = { createGuestCores = it.filter(Char::isDigit).take(3) }, label = { Text("CPU jezgre") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !createGuestBusy)
                        OutlinedTextField(value = createGuestMemory, onValueChange = { createGuestMemory = it.filter(Char::isDigit).take(7) }, label = { Text("RAM (MB)") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !createGuestBusy)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = createGuestDisk, onValueChange = { createGuestDisk = it.filter(Char::isDigit).take(5) }, label = { Text("Disk (GB)") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !createGuestBusy)
                        OutlinedTextField(value = createGuestStorage, onValueChange = { createGuestStorage = it.take(80) }, label = { Text("Storage") }, supportingText = { Text("Dostupni: " + storage.filter { it.active && it.enabled }.map { it.storage }.distinct().joinToString()) }, singleLine = true, modifier = Modifier.weight(1f), enabled = !createGuestBusy)
                    }
                    OutlinedTextField(value = createGuestBridge, onValueChange = { createGuestBridge = it.take(80) }, label = { Text("Mrežni bridge") }, supportingText = { Text("Najčešće vmbr0") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !createGuestBusy)
                    if (createGuestKind == "lxc") {
                        OutlinedTextField(value = createGuestTemplate, onValueChange = { createGuestTemplate = it.take(240) }, label = { Text("LXC template") }, placeholder = { Text("local:vztmpl/debian-12-standard_*.tar.zst") }, supportingText = { Text("Mora već postojati na Proxmox storageu.") }, modifier = Modifier.fillMaxWidth(), enabled = !createGuestBusy)
                        OutlinedTextField(value = createGuestPassword, onValueChange = { createGuestPassword = it }, label = { Text("Root lozinka za LXC") }, supportingText = { Text("Najmanje 8 znakova") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !createGuestBusy)
                    } else {
                        Text("VM će dobiti prazan disk. Prije prvog pokretanja dodaj instalacijski ISO kroz Proxmox ili konfiguraciju VM-a.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (createGuestBusy) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !createGuestBusy && createGuestId.toIntOrNull() != null && createGuestName.isNotBlank() && createGuestNode.isNotBlank() && createGuestStorage.isNotBlank() && createGuestCores.toIntOrNull() != null && createGuestMemory.toIntOrNull() != null && createGuestDisk.toIntOrNull() != null && createGuestBridge.isNotBlank() && (createGuestKind != "lxc" || (createGuestTemplate.isNotBlank() && createGuestPassword.length >= 8)),
                    onClick = {
                        createGuestBusy = true
                        scope.launch {
                            try {
                                api.createGuest(base, connection, createGuestNode.trim(), createGuestKind, createGuestId.toInt(), createGuestName.trim(), createGuestCores.toInt(), createGuestMemory.toInt(), createGuestDisk.toInt(), createGuestStorage.trim(), createGuestBridge.trim(), createGuestTemplate.trim(), createGuestPassword)
                                actionMessage = "${if (createGuestKind == "lxc") "LXC" else "VM"} ${createGuestName.trim()} (VMID ${createGuestId.trim()}) uspješno je kreiran."
                                createGuestOpen = false
                                onRefresh()
                            } catch (e: Exception) {
                                actionMessage = e.message ?: "Kreiranje resursa nije uspjelo."
                            } finally { createGuestBusy = false }
                        }
                    }
                ) { Text(if (createGuestBusy) "Kreiranje..." else "Kreiraj resurs") }
            },
            dismissButton = { TextButton(enabled = !createGuestBusy, onClick = { createGuestOpen = false }) { Text("Odustani") } }
        )
    }

    if (deleteGuestVm != null) {
        val target = deleteGuestVm!!
        AlertDialog(
            onDismissRequest = { if (!deleteGuestBusy) deleteGuestVm = null },
            title = { Text("Trajno obriši ${target.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${target.type.uppercase()} • VMID ${target.vmid} • ${target.node}")
                    Text("Ova radnja trajno briše resurs i njegove pridružene diskove. Ne može se poništiti.", color = MaterialTheme.colorScheme.error)
                    Text("Za potvrdu upiši VMID ${target.vmid}.")
                    OutlinedTextField(value = deleteGuestConfirmation, onValueChange = { deleteGuestConfirmation = it.filter(Char::isDigit).take(9) }, label = { Text("Potvrdi VMID") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !deleteGuestBusy)
                    if (deleteGuestBusy) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                }
            },
            confirmButton = {
                TextButton(enabled = !deleteGuestBusy && !target.isRunning && deleteGuestConfirmation == target.vmid.toString(), onClick = {
                    deleteGuestBusy = true
                    scope.launch {
                        try {
                            api.deleteGuest(base, connection, target)
                            actionMessage = "${target.name} (VMID ${target.vmid}) je obrisan."
                            deleteGuestVm = null
                            onRefresh()
                        } catch (e: Exception) {
                            actionMessage = e.message ?: "Brisanje nije uspjelo."
                        } finally { deleteGuestBusy = false }
                    }
                }) { Text(if (deleteGuestBusy) "Brišem..." else "Trajno obriši") }
            },
            dismissButton = { TextButton(enabled = !deleteGuestBusy, onClick = { deleteGuestVm = null }) { Text("Odustani") } }
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
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Lan, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(7.dp))
                                    Text("IP adrese", style = MaterialTheme.typography.titleSmall)
                                }
                                when {
                                    detailsIpLoading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Text("Dohvaćam IP adrese…", style = MaterialTheme.typography.bodySmall)
                                    }
                                    detailsIps.isNotEmpty() -> detailsIps.forEach { ip ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(ip, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                            IconButton(onClick = { scope.launch { androidx.compose.ui.platform.LocalClipboardManager.current } }) { }
                                        }
                                    }
                                    else -> Text(detailsIpMessage ?: "IP adresa nije dostupna.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeManagementDialog(base: String, connection: ProxmoxConnection, node: ProxmoxNode, onShell: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("summary") }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var logSearch by remember { mutableStateOf("") }
    var diskSearch by remember { mutableStateOf("") }
    var diskTypeFilter by remember { mutableStateOf("all") }
    var networkSearch by remember { mutableStateOf("") }
    var updates by remember { mutableStateOf<List<Pair<String,String>>>(emptyList()) }
    var disks by remember { mutableStateOf<List<String>>(emptyList()) }
    var network by remember { mutableStateOf<List<String>>(emptyList()) }
    var firewall by remember { mutableStateOf<List<ProxmoxFirewallRule>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<ProxmoxTask>>(emptyList()) }
    var selectedTask by remember { mutableStateOf<ProxmoxTask?>(null) }
    var taskLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var taskLogBusy by remember { mutableStateOf(false) }
    var taskLogError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmNodeAction by remember { mutableStateOf<String?>(null) }

    fun matchesDiskType(line: String): Boolean = when (diskTypeFilter) {
        "zfs" -> line.startsWith("ZFS POOL", ignoreCase = true)
        "physical" -> line.startsWith("FIZIČKI DISK", ignoreCase = true)
        else -> true
    }

    fun loadSelectedTab() {
        if (!node.status.equals("online", true)) {
            message = "Node '${node.node}' je offline ili nedostupan. Provjeri napajanje i mrežu; ostali čvorovi klastera mogu i dalje raditi."
            return
        }
        busy = true
        scope.launch {
            try {
                when (tab) {
                    "logs" -> logs = api.getNodeSyslog(base, connection, node)
                    "updates" -> updates = api.getNodeAptUpdates(base, connection, node)
                    "disks" -> disks = api.getNodeDisks(base, connection, node)
                    "network" -> network = api.getNodeNetwork(base, connection, node)
                    "firewall" -> firewall = api.getNodeFirewallRules(base, connection, node)
                    "tasks" -> tasks = api.getNodeTasks(base, connection, node.node, 100)
                }
                message = null
            } catch (e: Exception) {
                message = e.message ?: "Učitavanje nije uspjelo."
            } finally {
                busy = false
            }
        }
    }
    fun openTaskLog(task: ProxmoxTask) {
        selectedTask = task
        taskLog = emptyList()
        taskLogError = null
        taskLogBusy = true
        scope.launch {
            try {
                taskLog = api.getTaskLog(base, connection, node.node, task.upid)
                if (taskLog.isEmpty()) taskLogError = "Proxmox nije vratio zapise za ovaj zadatak."
            } catch (e: Exception) {
                taskLogError = e.message ?: "Dohvat loga nije uspio."
            } finally { taskLogBusy = false }
        }
    }
    LaunchedEffect(tab, node.node, node.status) {
        if (tab != "summary" && node.status.equals("online", true)) loadSelectedTab()
        else if (tab != "summary") message = "Node '${node.node}' je offline ili nedostupan. Podaci ovog čvora neće se učitavati dok se ne vrati online."
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(node.node, style = MaterialTheme.typography.titleLarge)
                        Text("Upravljanje Proxmox nodeom", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, contentDescription = "Natrag") } },
                actions = { IconButton(onClick = { if (tab == "summary") loadSelectedTab() else loadSelectedTab() }, enabled = !busy) { Icon(Icons.Default.Refresh, contentDescription = "Osvježi") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    Triple("summary", "Pregled", Icons.Default.Dashboard),
                    Triple("logs", "Syslog", Icons.Default.Article),
                    Triple("updates", "Update", Icons.Default.SystemUpdate),
                    Triple("disks", "Diskovi", Icons.Default.Storage),
                    Triple("network", "Mreža", Icons.Default.Lan),
                    Triple("firewall", "Firewall", Icons.Default.Security),
                    Triple("tasks", "Zadaci", Icons.Default.History)
                ).forEach { (id, label, icon) ->
                    FilterChip(
                        selected = tab == id,
                        onClick = { tab = id },
                        enabled = !busy,
                        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text(label) }
                    )
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message != null) Text(message!!, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.error)
            when (tab) {
                "summary" -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = CircleShape, color = if (node.status.equals("online", true)) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer, modifier = Modifier.size(48.dp)) {
                                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Computer, contentDescription = null) }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(node.node, style = MaterialTheme.typography.titleLarge)
                                        Text(node.status.uppercase(), color = if (node.status.equals("online", true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                HorizontalDivider()
                                val ramRatio = if (node.maxMem > 0) node.mem.toFloat() / node.maxMem.toFloat() else 0f
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Card(
                                        Modifier.weight(1f),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                                Spacer(Modifier.width(6.dp))
                                                Text("CPU", style = MaterialTheme.typography.labelLarge)
                                            }
                                            Text("${"%.1f".format(node.cpu * 100)}%", style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
                                            LinearProgressIndicator(progress = { node.cpu.coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
                                            Text("${node.maxCpu} jezgri", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Card(
                                        Modifier.weight(1f),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                                Spacer(Modifier.width(6.dp))
                                                Text("RAM", style = MaterialTheme.typography.labelLarge)
                                            }
                                            Text("${(ramRatio * 100).toInt()}%", style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
                                            LinearProgressIndicator(progress = { ramRatio.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                            Text("${formatBytes(node.mem)} / ${formatBytes(node.maxMem)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                        }
                                    }
                                }
                                val uptimeSeconds = node.uptime.toLong().coerceAtLeast(0L)
                                val uptimeLabel = when {
                                    uptimeSeconds >= 86400L -> "${uptimeSeconds / 86400L} d ${(uptimeSeconds % 86400L) / 3600L} h"
                                    uptimeSeconds >= 3600L -> "${uptimeSeconds / 3600L} h ${(uptimeSeconds % 3600L) / 60L} min"
                                    uptimeSeconds >= 60L -> "${uptimeSeconds / 60L} min ${uptimeSeconds % 60L} s"
                                    else -> "${uptimeSeconds} s"
                                }
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.medium
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.AccessTime, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Vrijeme rada", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.weight(1f))
                                        Text(uptimeLabel, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.extraLarge,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                        }
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("Brze radnje", style = MaterialTheme.typography.titleMedium)
                                        Text("Izravne radnje za ovaj node", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Button(onClick = onShell, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = node.status.equals("online", true) && !busy) {
                                    Icon(Icons.Default.Terminal, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Otvori terminal / Shell")
                                    Spacer(Modifier.weight(1f))
                                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(
                                        onClick = { confirmNodeAction = "reboot" },
                                        enabled = !busy && node.status.equals("online", true),
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                                    ) {
                                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Restart", maxLines = 1)
                                    }
                                    OutlinedButton(
                                        onClick = { confirmNodeAction = "shutdown" },
                                        enabled = !busy && node.status.equals("online", true),
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Ugasi", maxLines = 1)
                                    }
                                }
                                Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f), shape = MaterialTheme.shapes.medium) {
                                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Top) {
                                        Icon(Icons.Default.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Restart ili gašenje prekida rad svih VM-ova i LXC-ova na ovom nodeu.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("UPRAVLJANJE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.2.sp)
                                Text("Alati za node", style = MaterialTheme.typography.titleMedium)
                            }
                            Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Odaberi kartice na vrhu za syslog, nadogradnje, diskove, mrežu, firewall i povijest zadataka.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                "logs" -> if (logs.isEmpty() && busy) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } else if (logs.isEmpty()) EmptyNodeSection("Nema syslog zapisa za prikaz ili korisnik nema potrebne dozvole.") else LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("SISTEMSKI ZAPISI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.2.sp)
                                Text("${logs.size} zapisa učitano", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.Article, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = logSearch,
                            onValueChange = { logSearch = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Pretraži syslog") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (logSearch.isNotEmpty()) {
                                    IconButton(onClick = { logSearch = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Očisti pretragu")
                                    }
                                }
                            }
                        )
                    }
                    if (logs.none { it.contains(logSearch, ignoreCase = true) }) {
                        item {
                            EmptyNodeSection(if (logSearch.isBlank()) "Nema syslog zapisa za prikaz." else "Nema zapisa koji odgovaraju pretrazi.")
                        }
                    } else {
                        items(logs.filter { it.contains(logSearch, ignoreCase = true) }) { logLine ->
                            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
                                    Icon(Icons.Default.Article, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(17.dp))
                                    Spacer(Modifier.width(10.dp))
                                    SelectionContainer {
                                        Text(logLine, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
                "disks" -> if (disks.isEmpty() && !busy) EmptyNodeSection("Nema podataka o diskovima ili korisnik nema potrebne dozvole.") else LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("POHRANA NODEA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.2.sp)
                                Text("${disks.size} zapisa o pohrani", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = diskSearch,
                            onValueChange = { diskSearch = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Pretraži diskove i ZFS poolove") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (diskSearch.isNotEmpty()) IconButton(onClick = { diskSearch = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Očisti pretragu")
                                }
                            }
                        )
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = diskTypeFilter == "all",
                                onClick = { diskTypeFilter = "all" },
                                label = { Text("Sve (${disks.size})") }
                            )
                            FilterChip(
                                selected = diskTypeFilter == "physical",
                                onClick = { diskTypeFilter = "physical" },
                                label = { Text("Fizički diskovi (${disks.count { it.startsWith("FIZIČKI DISK", ignoreCase = true) }})") }
                            )
                            FilterChip(
                                selected = diskTypeFilter == "zfs",
                                onClick = { diskTypeFilter = "zfs" },
                                label = { Text("ZFS poolovi (${disks.count { it.startsWith("ZFS POOL", ignoreCase = true) }})") }
                            )
                        }
                    }
                    if (disks.none { it.contains(diskSearch, ignoreCase = true) && matchesDiskType(it) }) {
                        item {
                            EmptyNodeSection(
                                when {
                                    disks.isEmpty() -> "Proxmox nije vratio fizičke diskove ni ZFS poolove. Ako je Nextcloud pohrana dataset unutar poola, provjeri i karticu Pohrana."
                                    diskSearch.isNotBlank() -> "Nema diskova ili ZFS poolova koji odgovaraju pretrazi."
                                    diskTypeFilter == "zfs" -> "Nema vidljivih ZFS poolova. Dataseti i mount točke mogu biti prikazani u Pohrana ako su registrirani kao Proxmox storage."
                                    else -> "Nema diskova za prikaz u ovom filtru."
                                }
                            )
                        }
                    } else {
                        items(disks.filter { it.contains(diskSearch, ignoreCase = true) && matchesDiskType(it) }) { diskLine ->
                            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
                                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    SelectionContainer { Text(diskLine, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium) }
                                }
                            }
                        }
                    }
                }
                "network" -> if (network.isEmpty() && !busy) EmptyNodeSection("Nema mrežnih sučelja ili korisnik nema potrebne dozvole.") else LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("MREŽNA SUČELJA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.2.sp)
                                Text("${network.size} zapisa o sučeljima", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.Lan, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = networkSearch,
                            onValueChange = { networkSearch = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Pretraži mrežna sučelja") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (networkSearch.isNotEmpty()) IconButton(onClick = { networkSearch = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Očisti pretragu")
                                }
                            }
                        )
                    }
                    if (network.none { it.contains(networkSearch, ignoreCase = true) }) {
                        item { EmptyNodeSection(if (networkSearch.isBlank()) "Nema mrežnih sučelja za prikaz." else "Nema sučelja koja odgovaraju pretrazi.") }
                    } else {
                        items(network.filter { it.contains(networkSearch, ignoreCase = true) }) { networkLine ->
                            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
                                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Lan, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    SelectionContainer { Text(networkLine, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium) }
                                }
                            }
                        }
                    }
                }
                "firewall" -> if (firewall.isEmpty() && !busy) EmptyNodeSection("Nema pravila node firewalla ili korisnik nema potrebne dozvole.") else LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("SIGURNOSNA PRAVILA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.2.sp)
                                Text("Firewall nodea", style = MaterialTheme.typography.titleMedium)
                            }
                            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text("${firewall.size}", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                    items(firewall) { rule ->
                        val enabledColor = if (rule.enable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Surface(shape = MaterialTheme.shapes.medium, color = enabledColor.copy(alpha = 0.13f), modifier = Modifier.size(40.dp)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Security, contentDescription = null, tint = enabledColor, modifier = Modifier.size(21.dp))
                                        }
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text("Pravilo #${rule.pos}", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                        Text(rule.type.ifBlank { "Pravilo firewall" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Surface(shape = MaterialTheme.shapes.small, color = enabledColor.copy(alpha = 0.13f)) {
                                        Text(if (rule.enable) "AKTIVNO" else "ISKLJUČENO", Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = enabledColor)
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Akcija", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(8.dp))
                                    Text(rule.action.uppercase().ifBlank { "—" }, style = MaterialTheme.typography.labelLarge, color = enabledColor)
                                    Spacer(Modifier.weight(1f))
                                    rule.proto.takeIf { it.isNotBlank() }?.let { Text("Protokol: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                val details = listOf(
                                    rule.source.takeIf { it.isNotBlank() }?.let { "Izvor" to it },
                                    rule.dest.takeIf { it.isNotBlank() }?.let { "Odredište" to it },
                                    rule.dport.takeIf { it.isNotBlank() }?.let { "Port" to it },
                                    rule.comment.takeIf { it.isNotBlank() }?.let { "Opis" to it }
                                ).filterNotNull()
                                if (details.isNotEmpty()) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    details.forEach { (label, value) ->
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                            Text(label, modifier = Modifier.width(88.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            SelectionContainer { Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                "tasks" -> if (tasks.isEmpty() && !busy) EmptyNodeSection("Nema zadataka za prikaz ili korisnik nema potrebne dozvole.") else LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
                ) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("POVIJEST ZADATAKA", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.3.sp)
                                Text("Odaberi zadatak za pregled detaljnog loga.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text("${tasks.size}", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                    items(tasks) { task ->
                        val statusText = task.status.ifBlank { task.exitStatus.ifBlank { "Status nepoznat" } }
                        val success = statusText.contains("ok", ignoreCase = true) || statusText.contains("success", ignoreCase = true)
                        val failed = statusText.contains("error", ignoreCase = true) || statusText.contains("fail", ignoreCase = true) || statusText.contains("failed", ignoreCase = true)
                        val statusColor = if (success) androidx.compose.ui.graphics.Color(0xFF35B879) else if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        val startedAt = if (task.startTime > 0) java.text.SimpleDateFormat("dd.MM.yyyy • HH:mm", java.util.Locale.getDefault()).format(java.util.Date(task.startTime * 1000)) else "Vrijeme nije dostupno"
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { openTaskLog(task) },
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Surface(
                                        modifier = Modifier.size(42.dp),
                                        shape = MaterialTheme.shapes.medium,
                                        color = statusColor.copy(alpha = 0.13f)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                if (success) Icons.Default.CheckCircle else if (failed) Icons.Default.ErrorOutline else Icons.Default.History,
                                                contentDescription = null,
                                                tint = statusColor,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(task.type.ifBlank { "Zadatak Proxmoxa" }, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                        Text("Korisnik: " + task.user.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Surface(shape = MaterialTheme.shapes.small, color = statusColor.copy(alpha = 0.13f)) {
                                        Text(statusText, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = statusColor, maxLines = 1)
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(startedAt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(task.upid, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Pregledaj log", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                "updates" -> {
                    if (updates.isEmpty() && !busy) {
                        EmptyNodeSection("Node je ažuran ili korisnik nema potrebne dozvole za pregled nadogradnji.")
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize().padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            item {
                                Card(
                                    Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Surface(
                                            modifier = Modifier.size(46.dp),
                                            shape = MaterialTheme.shapes.medium,
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
                                            }
                                        }
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text("Nadogradnje paketa", style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                if (updates.isEmpty()) "Provjera paketa" else "${updates.size} dostupnih paketa",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(onClick = { loadSelectedTab() }, enabled = !busy) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Provjeri nadogradnje")
                                        }
                                    }
                                }
                            }
                            if (updates.isNotEmpty()) {
                                item {
                                    Text(
                                        "DOSTUPNI PAKETI",
                                        Modifier.padding(start = 4.dp, top = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        letterSpacing = 1.1.sp
                                    )
                                }
                                items(updates) { update ->
                                    Card(
                                        Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.large,
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Surface(
                                                modifier = Modifier.size(40.dp),
                                                shape = MaterialTheme.shapes.medium,
                                                color = MaterialTheme.colorScheme.secondaryContainer
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Default.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                                }
                                            }
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                Text(update.first.ifBlank { "Nepoznat paket" }, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                                Text("Dostupna verzija", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Surface(
                                                shape = MaterialTheme.shapes.medium,
                                                color = MaterialTheme.colorScheme.tertiaryContainer
                                            ) {
                                                Text(
                                                    update.second.ifBlank { "—" },
                                                    Modifier.widthIn(max = 132.dp).padding(horizontal = 10.dp, vertical = 7.dp),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                    maxLines = 2
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedTask?.let { task ->
        AlertDialog(
            onDismissRequest = { if (!taskLogBusy) selectedTask = null },
            title = { Text("Log zadatka: ${task.type.ifBlank { "Zadatak" }}") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(task.upid, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(8.dp))
                    if (taskLogBusy) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                    else if (taskLogError != null) Text(taskLogError!!, color = MaterialTheme.colorScheme.error)
                    else LazyColumn(Modifier.heightIn(max = 420.dp)) { items(taskLog) { line -> Text(line, style = MaterialTheme.typography.bodySmall); HorizontalDivider() } }
                }
            },
            confirmButton = { TextButton(onClick = { selectedTask = null }, enabled = !taskLogBusy) { Text("Zatvori") } },
            dismissButton = { TextButton(onClick = { openTaskLog(task) }, enabled = !taskLogBusy) { Text("Osvježi log") } }
        )
    }
    if (confirmNodeAction != null) {
        val action = confirmNodeAction!!
        AlertDialog(
            onDismissRequest = { if (!busy) confirmNodeAction = null },
            title = { Text(if (action == "reboot") "Restartaj node ${node.node}?" else "Ugasi node ${node.node}?") },
            text = { Text(if (action == "reboot") "Restart će prekinuti rad svih VM-ova i LXC-ova na ovom nodeu." else "Gašenje će zaustaviti cijeli node i sve VM-ove/LXC-ove na njemu.") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                confirmNodeAction = null
                busy = true
                message = null
                scope.launch {
                    try { api.controlNode(base, connection, node, action); message = if (action == "reboot") "Zahtjev za restart nodea poslan." else "Zahtjev za gašenje nodea poslan." }
                    catch (e: Exception) { message = e.message ?: "Radnja nije uspjela." }
                    finally { busy = false }
                }
            }) { Text(if (action == "reboot") "Potvrdi restart" else "Potvrdi gašenje") } },
            dismissButton = { TextButton(onClick = { confirmNodeAction = null }, enabled = !busy) { Text("Odustani") } }
        )
    }
}

@Composable
private fun EmptyNodeSection(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeShellScreen(base: String, connection: ProxmoxConnection, node: ProxmoxNode, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val terminalFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("console_state", Context.MODE_PRIVATE) }
    val stateKey = remember(base, node.node) { "node_shell_" + base.hashCode() + "_" + node.node }
    var output by remember(stateKey) { mutableStateOf(prefs.getString(stateKey, "") ?: "") }
    val latestOutput by rememberUpdatedState(output)
    val frameDecoder = remember(base, node.node) { TermproxyFrameDecoder() }
    var connected by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var ctrlModifierActive by remember { mutableStateOf(false) }
    val terminalBg = androidx.compose.ui.graphics.Color(0xFF020506)
    val terminalFg = androidx.compose.ui.graphics.Color(0xFFD6F4E1)
    val terminalEmulator = remember(base, node.node) {
        org.connectbot.terminal.TerminalEmulatorFactory.create(
            initialRows = 32,
            initialCols = 100,
            defaultForeground = terminalFg,
            defaultBackground = terminalBg,
            onKeyboardInput = { bytes ->
                val input = bytes.toString(Charsets.UTF_8)
                if (ctrlModifierActive) {
                    val character = input.singleOrNull()
                    if (character != null && character.isLetter() && character.code < 128) {
                        console.send((character.uppercaseChar().code - 64).toChar().toString())
                    } else {
                        console.send(input)
                    }
                    ctrlModifierActive = false
                } else {
                    console.send(input)
                }
            },
            onResize = { dimensions -> console.resize(dimensions.columns, dimensions.rows) }
        )
    }

    fun connect() {
        if (connecting) return
        connecting = true
        error = null
        scope.launch {
            try {
                console.openNode(base, connection, node, { bytes ->
                    scope.launch {
                        val message = bytes.toString(Charsets.UTF_8)
                        if (message.trim() == "OK") {
                            connected = true
                        } else {
                            val payloads = frameDecoder.feed(bytes)
                            if (payloads.isNotEmpty()) {
                                payloads.forEach { payload ->
                                    terminalEmulator.writeInput(payload)
                                    output = (output + payload.toString(Charsets.UTF_8)).takeLast(30000)
                                }
                                connected = true
                            } else if (message.startsWith("ERR") || message.contains("authentication failed", true)) {
                                error = message.take(500)
                            } else if (bytes.isNotEmpty() && !message.startsWith("0:")) {
                                terminalEmulator.writeInput(bytes)
                                output = (output + message).takeLast(30000)
                                connected = true
                            }
                        }
                    }
                }, { reason ->
                    scope.launch {
                        connected = false
                        if (reason != null) output = (output + "\n[Veza zatvorena: " + reason + "]\n").takeLast(30000)
                    }
                }, { e ->
                    scope.launch {
                        connected = false
                        error = e.message ?: "Greška node shell-a"
                    }
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
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> prefs.edit().putString(stateKey, latestOutput.takeLast(30000)).apply()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            prefs.edit().putString(stateKey, latestOutput.takeLast(30000)).apply()
            console.close()
        }
    }

    LaunchedEffect(node, connected) {
        if (connected) {
            while (true) {
                delay(15000)
                if (connected) console.ping()
            }
        }
    }

    LaunchedEffect(node) {
        var retryDelay = 1000L
        while (true) {
            delay(retryDelay)
            if (!connected && !connecting) {
                connect()
                retryDelay = (retryDelay * 2).coerceAtMost(15000L)
            } else if (connected) retryDelay = 1000L
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(node.node, maxLines = 1)
                        Text("NODE SHELL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Natrag") } },
                actions = {
                    Text(if (connected) "● LIVE" else if (connecting) "○ spajanje" else "○ offline", Modifier.padding(end = 2.dp), style = MaterialTheme.typography.labelSmall, color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { connect() }, enabled = !connecting) { Icon(Icons.Default.Refresh, contentDescription = "Ponovno poveži shell") }
                    IconButton(onClick = {
                        val pastedText = clipboard.getText()?.text.orEmpty()
                        if (pastedText.isNotEmpty()) { console.send(pastedText); terminalFocusRequester.requestFocus(); keyboardController?.show() }
                    }, enabled = connected && !clipboard.getText()?.text.isNullOrEmpty()) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Zalijepi iz međuspremnika")
                    }
                    IconButton(onClick = { terminalFocusRequester.requestFocus(); keyboardController?.show() }) { Icon(Icons.Default.Keyboard, contentDescription = "Prikaži tipkovnicu") }
                    IconButton(onClick = terminalEmulator::clearScreen) { Icon(Icons.Default.DeleteSweep, contentDescription = "Očisti terminal") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().imePadding().padding(padding).padding(horizontal = 8.dp, vertical = if (imeVisible) 0.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(if (imeVisible) 0.dp else 7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = if (connected) androidx.compose.ui.graphics.Color(0xFF123A2B) else MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(if (connected) androidx.compose.ui.graphics.Color(0xFF35D07F) else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                        Spacer(Modifier.width(7.dp))
                        Text(if (connected) "Povezano" else if (connecting) "Spajanje…" else "Nije povezano", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("NODE SHELL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(Modifier.fillMaxWidth().weight(1f), color = terminalBg, shape = MaterialTheme.shapes.medium, border = androidx.compose.foundation.BorderStroke(1.dp, if (connected) androidx.compose.ui.graphics.Color(0xFF235348) else MaterialTheme.colorScheme.outline)) {
                org.connectbot.terminal.Terminal(
                    terminalEmulator = terminalEmulator,
                    modifier = Modifier.fillMaxSize().padding(8.dp).focusRequester(terminalFocusRequester),
                    focusRequester = terminalFocusRequester,
                    onTerminalTap = { terminalFocusRequester.requestFocus(); keyboardController?.show() },
                    initialFontSize = 14.sp, minFontSize = 9.sp, maxFontSize = 24.sp,
                    backgroundColor = terminalBg, foregroundColor = terminalFg, keyboardEnabled = true, showSoftKeyboard = true
                )
            }
            if (imeVisible) Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        color = androidx.compose.ui.graphics.Color(0xFF17212D),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 3.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    listOf("ESC" to "\u001b", "/" to "/", "-" to "-", "HOME" to "\u001b[H", "↑" to "\u001b[A", "END" to "\u001b[F", "PGUP" to "\u001b[5~").forEach { (label, sequence) ->
                        TextButton(onClick = { ctrlModifierActive = false; console.send(sequence); keyboardController?.show() }, enabled = connected, contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp), modifier = Modifier.weight(1f).focusProperties { canFocus = false }) { Text(label, maxLines = 1, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelMedium) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    listOf("TAB" to "\t", "CTRL" to "CTRL", "ALT" to "\u001b", "←" to "\u001b[D", "↓" to "\u001b[B", "→" to "\u001b[C", "PGDN" to "\u001b[6~").forEach { (label, sequence) ->
                        TextButton(onClick = {
                            if (sequence == "CTRL") { ctrlModifierActive = !ctrlModifierActive; keyboardController?.show() }
                            else { ctrlModifierActive = false; console.send(sequence); keyboardController?.show() }
                        }, enabled = connected, contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp), modifier = Modifier.weight(1f).focusProperties { canFocus = false }) {
                            Text(if (label == "CTRL" && ctrlModifierActive) "CTRL ✓" else label, maxLines = 1, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
    onDelete: (ProxmoxVm) -> Unit,
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
    val vmCpuPercent = (vm.cpu * 100.0).toFloat().coerceIn(0f, 100f)
    val vmMemPercent = if (vm.maxMem > 0L) (vm.mem.toDouble() / vm.maxMem.toDouble() * 100.0).toFloat().coerceIn(0f, 100f) else 0f
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF10181D))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = MaterialTheme.shapes.large,
                    color = if (vm.isRunning) androidx.compose.ui.graphics.Color(0xFF123A2B) else androidx.compose.ui.graphics.Color(0xFF263139)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (vm.isQemu) Icons.Default.DesktopWindows else Icons.Default.Terminal, contentDescription = null, tint = if (vm.isRunning) androidx.compose.ui.graphics.Color(0xFF65E6A2) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(vm.name, style = MaterialTheme.typography.titleMedium)
                    Text(vm.type.uppercase() + "  •  VMID " + vm.vmid + "  •  " + vm.node, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(
                    shape = CircleShape,
                    color = if (vm.isRunning) androidx.compose.ui.graphics.Color(0xFF123A2B) else androidx.compose.ui.graphics.Color(0xFF263139)
                ) {
                    Text(
                        if (vm.isRunning) "RUNNING" else vm.status.uppercase(),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (vm.isRunning) androidx.compose.ui.graphics.Color(0xFF65E6A2) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CPU", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    Text("${"%.1f".format(vmCpuPercent)}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                LinearProgressIndicator(progress = { vmCpuPercent / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp), color = MaterialTheme.colorScheme.primary, trackColor = androidx.compose.ui.graphics.Color(0xFF293940))
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("RAM", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    Text("${vmMemPercent.toInt()}%", style = MaterialTheme.typography.labelLarge, color = androidx.compose.ui.graphics.Color(0xFF35D07F))
                }
                LinearProgressIndicator(progress = { vmMemPercent / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp), color = androidx.compose.ui.graphics.Color(0xFF35D07F), trackColor = androidx.compose.ui.graphics.Color(0xFF293940))
                Text(formatBytes(vm.mem) + " / " + formatBytes(vm.maxMem), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (vm.maxDisk > 0) Text("Disk: " + formatBytes(vm.maxDisk), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ onConsole(vm) }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Terminal, null); Spacer(Modifier.width(4.dp)); Text("Konzola") }
                OutlinedButton({ onDetails(vm) }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Tune, null); Spacer(Modifier.width(4.dp)); Text("Konfiguracija") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ onEditConfig(vm) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Uredi") }
                OutlinedButton({ onSnapshots(vm) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Snapshoti") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ onBackup(vm) }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Backup, null); Spacer(Modifier.width(4.dp)); Text("Backup") }
                OutlinedButton({ onFirewall(vm) }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Security, null); Spacer(Modifier.width(4.dp)); Text("Firewall") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ onClone(vm) }, enabled = !busy && vm.isQemu, modifier = Modifier.weight(1f)) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(4.dp)); Text("Kloniraj") }
                OutlinedButton({ onMigrate(vm) }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Default.SwapHoriz, null); Spacer(Modifier.width(4.dp)); Text("Migriraj") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    { showSnapshot = true; snapshotName = ""; snapshotDescription = "" },
                    enabled = !busy, modifier = Modifier.weight(1f)
                ) { Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(4.dp)); Text("Novi snapshot") }
                OutlinedButton({ onTasks(vm) }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Default.History, null); Spacer(Modifier.width(4.dp)); Text("Zadaci") }
            }
            OutlinedButton({ onDelete(vm) }, enabled = !busy && !vm.isRunning, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Default.DeleteForever, null)
                Spacer(Modifier.width(4.dp))
                Text(if (vm.isRunning) "Zaustavi prije brisanja" else "Trajno obriši VM / LXC")
            }
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

@Composable
private fun GuestListRow(vm: ProxmoxVm, onClick: () -> Unit) {
    val cpuPercent = (vm.cpu * 100.0).toFloat().coerceIn(0f, 100f)
    val memoryPercent = if (vm.maxMem > 0L) (vm.mem.toDouble() / vm.maxMem.toDouble() * 100.0).toFloat().coerceIn(0f, 100f) else 0f
    val darkCard = androidx.compose.ui.graphics.Color(0xFF1D1D1F)
    val track = androidx.compose.ui.graphics.Color(0xFF38383B)
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = darkCard)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (vm.isQemu) Icons.Default.Dns else Icons.Default.Inventory2, contentDescription = null, tint = if (vm.isRunning) androidx.compose.ui.graphics.Color(0xFF35C759) else androidx.compose.ui.graphics.Color(0xFF77777A), modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(12.dp))
                Text(vm.vmid.toString(), style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color(0xFFF2F2F7), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.width(18.dp))
                Text(vm.name, style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color(0xFFE5E5EA), maxLines = 1, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight, contentDescription = "Otvori " + vm.name, tint = androidx.compose.ui.graphics.Color(0xFF77777A))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(progress = { cpuPercent / 100f }, modifier = Modifier.fillMaxWidth().height(5.dp), color = androidx.compose.ui.graphics.Color(0xFF35C759), trackColor = track)
                    Text("CPU " + "%.0f".format(cpuPercent) + "%", style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color(0xFF98989D), maxLines = 1)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(progress = { memoryPercent / 100f }, modifier = Modifier.fillMaxWidth().height(5.dp), color = androidx.compose.ui.graphics.Color(0xFF35C759), trackColor = track)
                    Text(memoryPercent.toInt().toString() + "% od " + formatBytes(vm.maxMem), style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color(0xFF98989D), maxLines = 1)
                }
            }
        }
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


/**
 * Proxmox termproxy frames are length-prefixed: "0:<byte-count>:<raw bytes>".
 * WebSocket messages usually contain complete frames, but buffering here also
 * handles a header or payload split across callbacks without corrupting UTF-8/ANSI data.
 */
private class TermproxyFrameDecoder {
    private var pending = ByteArray(0)

    @Synchronized
    fun feed(incoming: ByteArray): List<ByteArray> {
        if (incoming.isEmpty()) return emptyList()
        // Handshake/error messages are plain text, not termproxy data frames.
        if (pending.isEmpty() && !(incoming.size >= 2 &&
                incoming[0] == '0'.code.toByte() && incoming[1] == ':'.code.toByte())) {
            return emptyList()
        }
        val bytes = pending + incoming
        val payloads = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            if (offset + 2 > bytes.size) break
            if (bytes[offset] != '0'.code.toByte() || bytes[offset + 1] != ':'.code.toByte()) {
                // Unexpected data: drop stale partial data and let caller handle a plain message.
                pending = ByteArray(0)
                return payloads
            }
            var separator = offset + 2
            while (separator < bytes.size &&
                bytes[separator] in '0'.code.toByte()..'9'.code.toByte()) separator++
            if (separator == bytes.size) break // header split between callbacks
            if (separator == offset + 2 || bytes[separator] != ':'.code.toByte()) {
                pending = ByteArray(0)
                return payloads
            }
            val count = bytes.copyOfRange(offset + 2, separator)
                .toString(Charsets.US_ASCII).toIntOrNull()
            if (count == null || count < 0 || count > 4 * 1024 * 1024) {
                pending = ByteArray(0)
                return payloads
            }
            val payloadStart = separator + 1
            if (bytes.size - payloadStart < count) break // payload split between callbacks
            payloads += bytes.copyOfRange(payloadStart, payloadStart + count)
            offset = payloadStart + count
        }
        pending = bytes.copyOfRange(offset, bytes.size)
        return payloads
    }

    @Synchronized
    fun reset() { pending = ByteArray(0) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleScreen(base:String,connection:ProxmoxConnection,vm:ProxmoxVm,onBack:()->Unit){
    val scope=rememberCoroutineScope()
    val lifecycleOwner=androidx.lifecycle.compose.LocalLifecycleOwner.current
    val clipboard=androidx.compose.ui.platform.LocalClipboardManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val terminalFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val context=androidx.compose.ui.platform.LocalContext.current
    val consolePrefs=remember{context.getSharedPreferences("console_state", Context.MODE_PRIVATE)}
    val stateKey=remember(base,vm.node,vm.vmid){"console_"+base.hashCode()+"_"+vm.node+"_"+vm.vmid}
    var output by remember(stateKey){mutableStateOf(consolePrefs.getString(stateKey,"")?:"")}
    val latestOutput by rememberUpdatedState(output)
    val frameDecoder = remember(base, vm.node, vm.vmid) { TermproxyFrameDecoder() }
    var connected by remember{mutableStateOf(false)}
    var connecting by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    var ctrlModifierActive by remember{mutableStateOf(false)}
    val terminalBg=androidx.compose.ui.graphics.Color(0xFF020506)
    val terminalFg=androidx.compose.ui.graphics.Color(0xFFD6F4E1)
    val terminalEmulator = remember(base, vm.node, vm.vmid) {
        org.connectbot.terminal.TerminalEmulatorFactory.create(
            initialRows = 32,
            initialCols = 100,
            defaultForeground = terminalFg,
            defaultBackground = terminalBg,
            onKeyboardInput = { bytes ->
                val input = bytes.toString(Charsets.UTF_8)
                if (ctrlModifierActive) {
                    val character = input.singleOrNull()
                    if (character != null && character.isLetter() && character.code < 128) {
                        console.send((character.uppercaseChar().code - 64).toChar().toString())
                    } else {
                        console.send(input)
                    }
                    ctrlModifierActive = false
                } else {
                    console.send(input)
                }
            },
            onResize = { dimensions -> console.resize(dimensions.columns, dimensions.rows) }
        )
    }

    fun connect(){
        if(connecting) return
        connecting=true
        error=null
        scope.launch{
            try{
                console.open(base,connection,vm,{b->
                    // OkHttp invokes WebSocket callbacks on its own thread. Keep all
                    // Compose state and terminal-emulator mutations on the UI dispatcher.
                    scope.launch {
                        val message=b.toString(Charsets.UTF_8)
                        if(message=="OK" || message.trim()=="OK") {
                            connected=true
                        } else {
                            val payloads=frameDecoder.feed(b)
                            if(payloads.isNotEmpty()) {
                                payloads.forEach { payload ->
                                    terminalEmulator.writeInput(payload)
                                    output=(output+payload.toString(Charsets.UTF_8)).takeLast(30000)
                                }
                                connected=true
                            } else if(message.startsWith("ERR") || message.contains("authentication failed",true)) {
                                error=message.take(500)
                            } else if (b.isNotEmpty() && !message.startsWith("0:")) {
                                terminalEmulator.writeInput(b)
                                output=(output+message).takeLast(30000)
                                connected=true
                            }
                        }
                    }
                },{reason->
                    scope.launch {
                        connected=false
                        if(reason!=null) output=(output+"\n[Veza zatvorena: "+reason+"]\n").takeLast(30000)
                    }
                },{e->
                    scope.launch {
                        connected=false
                        error=e.message?:"Greška konzole"
                    }
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
            } else if (connected) retryDelay = 1000L
        }
    }

    Scaffold(
        contentWindowInsets=WindowInsets(0.dp),
        topBar={TopAppBar(
            title={
                Column(verticalArrangement=Arrangement.spacedBy(1.dp)){
                    Text(vm.name, maxLines=1)
                    Text("KONZOLA · "+vm.node+" · "+vm.vmid,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,letterSpacing=1.sp)
                }
            },
            navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Natrag")}},
            actions={
                Text(if(connected)"● LIVE" else if(connecting)"○ spajanje" else "○ offline",Modifier.padding(end=2.dp),style=MaterialTheme.typography.labelSmall,color=if(connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick={connect()},enabled=!connecting){Icon(Icons.Default.Refresh,contentDescription="Ponovno poveži konzolu")}
                IconButton(
                    onClick = {
                        val text = clipboard.getText()?.text
                        if (connected && !text.isNullOrEmpty()) {
                            console.send(text)
                            terminalFocusRequester.requestFocus()
                            keyboardController?.show()
                        }
                    },
                    enabled = connected && !clipboard.getText()?.text.isNullOrEmpty()
                ) { Icon(Icons.Default.ContentPaste, contentDescription = "Zalijepi iz međuspremnika") }
                IconButton(onClick={
                    val pastedText=clipboard.getText()?.text.orEmpty()
                    if(pastedText.isNotEmpty()){console.send(pastedText);terminalFocusRequester.requestFocus();keyboardController?.show()}
                },enabled=connected&&!clipboard.getText()?.text.isNullOrEmpty()){Icon(Icons.Default.ContentPaste,contentDescription="Zalijepi iz međuspremnika")}
                IconButton(onClick={terminalFocusRequester.requestFocus();keyboardController?.show()}){Icon(Icons.Default.Keyboard,contentDescription="Prikaži tipkovnicu")}
                IconButton(onClick={terminalEmulator::clearScreen}){Icon(Icons.Default.DeleteSweep,contentDescription="Očisti terminal")}
            }
        )}
    ){p->
        Column(Modifier.fillMaxSize().imePadding().padding(p).padding(horizontal=8.dp, vertical=if (imeVisible) 0.dp else 6.dp), verticalArrangement=Arrangement.spacedBy(if (imeVisible) 0.dp else 7.dp)){
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = if (connected) androidx.compose.ui.graphics.Color(0xFF123A2B) else MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(if (connected) androidx.compose.ui.graphics.Color(0xFF35D07F) else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                        Spacer(Modifier.width(7.dp))
                        Text(if (connected) "Povezano" else if (connecting) "Spajanje…" else "Nije povezano", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("VMID ${vm.vmid}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(Modifier.fillMaxWidth().weight(1f), color = terminalBg, shape = MaterialTheme.shapes.medium, border = androidx.compose.foundation.BorderStroke(1.dp, if (connected) androidx.compose.ui.graphics.Color(0xFF235348) else MaterialTheme.colorScheme.outline)) {
                org.connectbot.terminal.Terminal(
                    terminalEmulator = terminalEmulator,
                    modifier = Modifier.fillMaxSize().padding(8.dp).focusRequester(terminalFocusRequester),
                    focusRequester = terminalFocusRequester,
                    onTerminalTap = { terminalFocusRequester.requestFocus(); keyboardController?.show() },
                    initialFontSize = 14.sp, minFontSize = 9.sp, maxFontSize = 24.sp,
                    backgroundColor = terminalBg, foregroundColor = terminalFg, keyboardEnabled = true, showSoftKeyboard = true
                )
            }
            if (imeVisible) Column(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xFF151D26), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    listOf("ESC" to "\u001b", "/" to "/", "-" to "-", "HOME" to "\u001b[H", "↑" to "\u001b[A", "END" to "\u001b[F", "PGUP" to "\u001b[5~").forEach { (label, sequence) ->
                        TextButton(onClick = { ctrlModifierActive = false; console.send(sequence); keyboardController?.show() }, enabled = connected, contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp), modifier = Modifier.weight(1f).focusProperties { canFocus = false }) { Text(label, maxLines = 1, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelMedium) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    listOf("TAB" to "\t", "CTRL" to "CTRL", "ALT" to "\u001b", "←" to "\u001b[D", "↓" to "\u001b[B", "→" to "\u001b[C", "PGDN" to "\u001b[6~").forEach { (label, sequence) ->
                        TextButton(onClick = {
                            if (sequence == "CTRL") { ctrlModifierActive = !ctrlModifierActive; keyboardController?.show() }
                            else { ctrlModifierActive = false; console.send(sequence); keyboardController?.show() }
                        }, enabled = connected, contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp), modifier = Modifier.weight(1f).focusProperties { canFocus = false }) {
                            Text(if (label == "CTRL" && ctrlModifierActive) "CTRL ✓" else label, maxLines = 1, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            if (error != null) { Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) { Text(error!!, modifier = Modifier.padding(10.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

