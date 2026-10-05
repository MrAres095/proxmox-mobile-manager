package com.ares.proxmoxmobilemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme()
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            var showSettings by remember { mutableStateOf(true) }
            var connectedBase by remember { mutableStateOf<String?>(null) }
            var nodes by remember { mutableStateOf<List<ProxmoxNode>>(emptyList()) }
            var loading by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var connection by remember {
                mutableStateOf(ProxmoxConnection("", "", "", ""))
            }
            val scope = rememberCoroutineScope()

            if (showSettings || connectedBase == null) {
                ConnectionScreen(
                    initial = connection,
                    loading = loading,
                    error = error,
                    onConnect = { newConnection ->
                        connection = newConnection
                        loading = true
                        error = null
                        scope.launch {
                            try {
                                val base = api.findReachableBase(newConnection)
                                    ?: error("Nijedan Proxmox URL nije dostupan.")
                                if (base != null) {
                                    connectedBase = base
                                    nodes = api.getNodes(base, newConnection)
                                    showSettings = false
                                }
                            } catch (e: Exception) {
                                error = e.message ?: "Greška pri povezivanju."
                            } finally {
                                loading = false
                            }
                        }
                    }
                )
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

    Scaffold(
        topBar = { TopAppBar(title = { Text("Proxmox Mobile Manager") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Poveži Proxmox", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Aplikacija prvo pokušava lokalnu adresu, a zatim udaljenu domenu.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(local, { local = it }, Modifier.fillMaxWidth(), label = { Text("Lokalni URL") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(remote, { remote = it }, Modifier.fillMaxWidth(), label = { Text("Udaljeni URL") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(tokenId, { tokenId = it }, Modifier.fillMaxWidth(), label = { Text("API token ID (npr. root@pam!mobile)") }, singleLine = true)
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
                onClick = {
                    onConnect(ProxmoxConnection(local, remote, tokenId, secret))
                }
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

@Composable
private fun Dashboard(
    base: String,
    nodes: List<ProxmoxNode>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    error: String?
) {
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
            if (!loading && nodes.isEmpty()) {
                item { Text("Nema pronađenih nodeova.") }
            }
        }
    }
}

private fun formatBytes(value: Long): String {
    if (value <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = value.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return "${"%.1f".format(v)} ${units[i]}"
}
