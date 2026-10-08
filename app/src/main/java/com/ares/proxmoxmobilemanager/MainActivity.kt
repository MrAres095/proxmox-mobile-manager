 LaunchedEffect(vm,live){if(live)while(true){delay(15000);if(live)console.ping()}}
 LaunchedEffect(vm){var retry=1000L;while(true){delay(retry);if(!live&&!connecting){connect();retry=(retry*2).coerceAtMost(15000L)}else if(live)retry=1000L}}
 Scaffold(
  topBar = {
   TopAppBar(
    colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color(0xFF111417), titleContentColor = androidx.compose.ui.graphics.Color.White),
    title = { Column { Text(vm.name); Text(vm.type.uppercase() + " • " + vm.node + " • VMID " + vm.vmid, style = MaterialTheme.typography.labelSmall) } },
    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Natrag", tint = androidx.compose.ui.graphics.Color.White) } },
    actions = {
     IconButton(onClick = { connect() }, enabled = !connecting) { Icon(Icons.Default.Refresh, "Reconnect", tint = androidx.compose.ui.graphics.Color.White) }
     IconButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(output)) }) { Icon(Icons.Default.ContentCopy, "Kopiraj", tint = androidx.compose.ui.graphics.Color.White) }
     Text(if (live) "● LIVE" else if (connecting) "○..." else "○ OFF", modifier = Modifier.padding(end = 8.dp))
    }
   )
  },
  topBar={TopAppBar(colors=TopAppBarDefaults.topAppBarColors(containerColor=androidx.compose.ui.graphics.Color(0xFF111417),titleContentColor=androidx.compose.ui.graphics.Color.White),title={Column{Text(vm.name);Text(vm.type.uppercase()+" • "+vm.node+" • VMID "+vm.vmid,style=MaterialTheme.typography.labelSmall)}},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Natrag",tint=androidx.compose.ui.graphics.Color.White)}},actions={IconButton(onClick={connect()},enabled=!connecting){Icon(Icons.Default.Refresh,"Reconnect",tint=androidx.compose.ui.graphics.Color.White)};IconButton(onClick={clipboard.setText(androidx.compose.ui.text.AnnotatedString(output))}){Icon(Icons.Default.ContentCopy,"Kopiraj",tint=androidx.compose.ui.graphics.Color.White)};Text(if(live)"● LIVE" else if(connecting)"○..." else "○ OFF",modifier=Modifier.padding(end=8.dp))}})},