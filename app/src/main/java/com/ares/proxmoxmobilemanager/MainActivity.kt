package com.ares.proxmoxmobilemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
            var connectedBase by remember { mutableStateOf<String?>(null) }
            var nodes by remember { mutableStateOf<List<ProxmoxNode>>(emptyList()) }
            var loading by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var connection by remember { mutableStateOf(ProxmoxConnection("", "", "", "")) }
            val scope = rememberCoroutineScope()

            if (showSettings || connectedBase == null) {
                ConnectionScreen(connection, loading, error) { newConnection ->
                    connection = newConnection
                    loading = true
                    error = null
                    scope.launch {
                        try {
                            val base = api.findReachableBase(newConnection)
                                ?: throw IllegalStateException("Nijedan Proxmox URL nije dostupan.")
                            connectedBase = base
                            nodes = api.getNodes(base, newConnection)
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
                    loading = loading,
                    onRefresh = {
                        loading = true
                        scope.launch {
                            try {
                                nodes = api.getNodes(connectedBase!!, connection)
                                error = null
                            } catch (e: Exception) {
                                error = e.message ?: "Greška."
                            } finally {
                                loading = false
                            }
                        }
                    },
                    onSettings = { showSettings = true },
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
    var local by remember { mutableStateOf(initial.localUrl) }
    var remote by remember { mutableStateOf(initial.remoteUrl) }
    var tokenId by remember { mutableStateOf(initial.tokenId) }
    var secret by remember { mutableStateOf(initial.tokenSecret) }

    Scaffold(topBar = { TopAppBar(title = { Text("Proxmox Mobile Manager") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Poveži Proxmox", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("Aplikacija prvo pokušava lokalnu adresu, a zatim udaljenu domenu.")
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(local, { local = it }, Modifier.fillMaxWidth(), label = { Text("Lokalni URL") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(remote, { remote = it }, Modifier.fillMaxWidth(), label = { Text("Udaljeni URL") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(tokenId, { tokenId = it }, Modifier.fillMaxWidth(), label = { Text("API token ID") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth(), label = { Text("API token secret") }, singleLine = true)
            Spacer(Modifier.height(18.dp))
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading,
                onClick = { onConnect(ProxmoxConnection(local, remote, tokenId, secret)) }
            ) {
                if (loading) CircularProgressIndicator(modifier = Modifier.height(20.dp))
                else {
                    Icon(Icons.Default.Cloud, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text("Poveži se")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dashboard(
    base: String,
    nodes: List<ProxmoxNode>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    error: String?
) {
    val context = LocalContext.current
    val updater = remember { UpdateManager(context) }
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateInstalling by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }

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
        }
    }
}

private fun formatBytes(value: Long): String {
    if (value <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = value.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "${"%.1f".format(v)} ${units[i]}"
}
