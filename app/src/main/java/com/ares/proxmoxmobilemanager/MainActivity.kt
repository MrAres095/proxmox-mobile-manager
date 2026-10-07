package com.ares.proxmoxmobilemanager

import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            var showSettings by remember { mutableStateOf(true) }
            var consoleVm by remember { mutableStateOf<ProxmoxVm?>(null) }
            var connectedBase by remember { mutableStateOf<String?>(null) }
            var nodes by remember { mutableStateOf<List<ProxmoxNode>>(emptyList()) }
            var vms by remember { mutableStateOf<List<ProxmoxVm>>(emptyList()) }
            var storage by remember { mutableStateOf<List<ProxmoxStorage>>(emptyList()) }
            var vmLoading by remember { mutableStateOf(false) }
            var loading by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var updatingAll by remember { mutableStateOf(false) }
            var updateStatuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("proxmox_connection", Context.MODE_PRIVATE) }
            var connection by remember { mutableStateOf(ProxmoxConnection(prefs.getString("localUrl", "") ?: "", prefs.getString("remoteUrl", "") ?: "", prefs.getString("username", "") ?: "", prefs.getString("password", "") ?: "", prefs.getString("tokenId", "") ?: "", prefs.getString("tokenSecret", "") ?: "")) }
            val scope = rememberCoroutineScope()

            if (consoleVm != null && connectedBase != null) { ConsoleScreen(connectedBase!!, connection, consoleVm!!, { console.close(); consoleVm=null }) } else if (showSettings || connectedBase == null) {
                ConnectionScreen(connection, loading, error) { newConnection ->
                    connection = newConnection
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
                    error = error
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionScreen(
    initial: ProxmoxConnection,
    loading: Boolean,
    error: String?,
    onConnect: (ProxmoxConnection) -> Unit
) {
    var local by remember { mutableStateOf(initial.localUrl.removePrefix("https://").removePrefix("http://").substringBefore(":8006")) }
    var localPort by remember { mutableStateOf("8006") }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var remote by remember { mutableStateOf(initial.remoteUrl) }
    var tokenId by remember { mutableStateOf(initial.tokenId) }
    var secret by remember { mutableStateOf(initial.tokenSecret) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Proxmox Mobile Manager") }) },
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
                        onConnect(ProxmoxConnection(endpoint, remote.trim().trimEnd('/'), username.trim(), password, tokenId.trim(), secret.trim()))
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
private fun Dashboard(
    base: String,
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
    onUpdateAll: () -> Unit,
    updateAllBusy: Boolean,
    updateStatuses: Map<String, String>,
    error: String?
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
                title = { Text("Proxmox") },
                actions = {
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, null) }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, null) }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(base, style = MaterialTheme.typography.labelMedium)
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
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

            item { Text("Virtualne mašine i LXC", style = MaterialTheme.typography.headlineSmall) }
            if (vmLoading && vms.isEmpty()) {
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
            }
            items(vms, key = { it.node + "-" + it.type + "-" + it.vmid }) { vm ->
                VmCard(vm, vmLoading, onVmAction, onConsole, onSnapshot, onDetails = { selected ->
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

            if (loading) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            items(nodes) { node ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(node.node, style = MaterialTheme.typography.titleLarge)
                        Text("Status: ${node.status}")
                        Text("CPU: ${"%.1f".format(node.cpu * 100)}% / ${node.maxCpu} CPU")
                        Text("RAM: ${formatBytes(node.mem)} / ${formatBytes(node.maxMem)}")
                        Text("Uptime: ${node.uptime}s")
                    }
                }
            }
            if (!loading && nodes.isEmpty()) item { Text("Nema pronađenih nodeova.") }
            item { Text("Storage", style = MaterialTheme.typography.headlineSmall) }
            items(storage, key = { it.node + "-" + it.storage }) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(s.storage, style = MaterialTheme.typography.titleMedium)
                        Text("${s.node} • ${s.type} • ${if (s.active) "active" else "inactive"}")
                        Text("Prostor: ${formatBytes(s.used)} / ${formatBytes(s.total)}")
                        Text("Slobodno: ${formatBytes(s.avail)}")
                    }
                }
            }
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


@Composable
private fun VmCard(
    vm: ProxmoxVm,
    busy: Boolean,
    onAction: (ProxmoxVm, VmAction) -> Unit,
    onConsole: (ProxmoxVm) -> Unit,
    onSnapshot: (ProxmoxVm, String, String) -> Unit,
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
 val scope=rememberCoroutineScope(); var output by remember{mutableStateOf("Spajanje na Proxmox konzolu...\n")}; var input by remember{mutableStateOf("")}; var connected by remember{mutableStateOf(false)}; var error by remember{mutableStateOf<String?>(null)}
 DisposableEffect(vm){ scope.launch{try{console.open(base,connection,vm,{b->val t=String(b,Charsets.UTF_8);if(t=="OK")connected=true else output=(output+t).takeLast(30000)},{reason->connected=false;output+="\n[Veza zatvorena"+(reason?.let{": "+it}?: "")+"]\n"},{e->connected=false;error=e.message?:"Greška konzole"})}catch(e:Exception){error=e.message?:"Spajanje na konzolu nije uspjelo."}};onDispose{console.close()} }
 Scaffold(topBar={TopAppBar(title={Text(vm.name+" • Console")},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Natrag")}},actions={Text(if(connected)"● LIVE" else "○ povezivanje",Modifier.padding(end=12.dp))})}){p->Column(Modifier.fillMaxSize().padding(p).padding(8.dp)){Surface(Modifier.fillMaxWidth().weight(1f)){Text(output,Modifier.fillMaxSize().padding(8.dp),style=MaterialTheme.typography.bodySmall)};if(error!=null)Text(error!!,color=MaterialTheme.colorScheme.error);Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(input,{input=it},Modifier.weight(1f),label={Text("Unos")},singleLine=true,enabled=connected);Spacer(Modifier.width(6.dp));Button({console.send(input+"\n");input=""},enabled=connected&&input.isNotEmpty()){Text("Pošalji")}};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){TextButton({console.send("\u0003")},enabled=connected){Text("Ctrl+C")};TextButton({console.send("\u0004")},enabled=connected){Text("Ctrl+D")};TextButton({console.send("\t")},enabled=connected){Text("Tab")};TextButton({console.send("\u001b[A")},enabled=connected){Text("↑")};TextButton({console.send("\u001b[B")},enabled=connected){Text("↓")}}}}}
