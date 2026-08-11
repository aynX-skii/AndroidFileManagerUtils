package com.aynux.afmu.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aynux.afmu.MainViewModel
import com.aynux.afmu.R
import com.aynux.afmu.core.AuthRequests
import com.aynux.afmu.core.Base32
import com.aynux.afmu.core.PeerRecord
import com.aynux.afmu.core.Prefs
import com.aynux.afmu.core.Discovery
import com.aynux.afmu.core.HttpServer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainViewModel.UiState,
    viewModel: MainViewModel,
    onPickFiles: () -> Unit,
    onRequestFullStorage: () -> Unit,
    onScanCode: () -> Unit,
) {
    val snackbars = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbars.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FileBridge") },
                actions = {
                    IconButton(onClick = { viewModel.refreshNetwork() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh_network))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ServerCard(state, viewModel) { copyToClipboard(context, it) }
            if (!state.fullStorageAccess) StorageAccessCard(onRequestFullStorage)
            SendCard(state, viewModel, onPickFiles, onScanCode)
            PairedDevicesCard(state, viewModel) { copyToClipboard(context, it) }
            SettingsCard(state, viewModel)
            LogCard(state, viewModel)
            Spacer(Modifier.height(24.dp))
        }
    }

    state.browse?.let { browse ->
        RemoteBrowser(browse, state.transfers, viewModel)
    }

    // Also reachable from the notification shade; this is the version for when the app
    // already happens to be open.
    state.pendingAuth?.let { request ->
        AuthRequestDialog(
            request = request,
            onAllow = { viewModel.approveAuth() },
            onDeny = { viewModel.denyAuth() },
        )
    }

    state.outgoingAuth?.let { pending ->
        OutgoingAuthDialog(pending) { viewModel.cancelAuthorization() }
    }

    if (state.scannerOpen) {
        ScannerScreen(
            onResult = { viewModel.onCodeScanned(it) },
            onClose = { viewModel.closeScanner() },
        )
    }
}

/**
 * The approval prompt (PROTOCOL.md §3.8). It states who is asking, from which address, and
 * shows the code the PC is displaying — the user's only defence against approving somebody
 * else's request is being able to compare the two.
 */
@Composable
private fun AuthRequestDialog(
    request: AuthRequests.Request,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        // Dismissing counts as denying. Silently leaving it pending would keep the PC
        // waiting on a prompt that is no longer on screen.
        onDismissRequest = onDeny,
        title = { Text(stringResource(R.string.auth_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.auth_dialog_body, request.name, request.host),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    request.code,
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.auth_dialog_code_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.auth_dialog_grant_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = onAllow) { Text(stringResource(R.string.auth_allow)) } },
        dismissButton = { TextButton(onClick = onDeny) { Text(stringResource(R.string.auth_deny)) } },
    )
}

/** The Compose clipboard API is suspend-only now; the platform service is simpler here. */
private fun copyToClipboard(context: Context, value: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("FileBridge", value))
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ServerCard(
    state: MainViewModel.UiState,
    viewModel: MainViewModel,
    onCopy: (String) -> Unit,
) {
    SectionCard(stringResource(R.string.receive_on_this_phone)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.serverRunning) stringResource(R.string.server_running, state.port)
                    else stringResource(R.string.server_stopped),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.network_label, state.network) +
                        if (state.serverRunning && !state.onLan) stringResource(R.string.no_lan_detected) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.serverRunning,
                onCheckedChange = { viewModel.setServerRunning(it) },
            )
        }

        if (state.serverRunning) {
            // Stays on screen the whole time the server is up (PROTOCOL.md §2.2). Saying
            // "plain HTTP" only in the docs means the person deciding whether to leave this
            // running never reads it.
            UnencryptedNotice()

            // Discovery replies carry no device name until this is tapped (§1.5).
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.pairingSecondsLeft > 0) {
                    FilledTonalButton(onClick = { viewModel.stopPairingMode() }) {
                        Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.discoverable_for, state.pairingSecondsLeft))
                    }
                } else {
                    OutlinedButton(onClick = { viewModel.startPairingMode() }) {
                        Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.make_discoverable))
                    }
                }
            }
            Text(
                stringResource(R.string.discoverable_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.urls.isEmpty()) {
                Text(
                    stringResource(R.string.no_usable_address) +
                        stringResource(R.string.usb_tethering_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.urls.forEachIndexed { index, url ->
                CopyRow(
                    label = if (index == 0) stringResource(R.string.open_in_pc_browser) else null,
                    value = url,
                    onCopy = onCopy,
                )
            }
            HorizontalDivider()
            CopyRow(label = stringResource(R.string.access_token), value = state.token, onCopy = onCopy)
            TextButton(onClick = { viewModel.regenerateToken() }) {
                Text(stringResource(R.string.generate_new_token))
            }
            Text(
                stringResource(R.string.incoming_files_land_in, state.inbox),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The permanent "this is not encrypted" banner (PROTOCOL.md §2.2).
 *
 * Deliberately not dismissible and not a one-time dialog: the fact does not go away while
 * the server runs, and a warning the user tapped through three weeks ago is not informed
 * consent today.
 */
@Composable
private fun UnencryptedNotice() {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.shapes.small,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.LockOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                stringResource(R.string.unencrypted_badge),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                stringResource(R.string.unencrypted_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun CopyRow(label: String?, value: String, onCopy: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(onClick = { onCopy(value) }) {
            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy))
        }
    }
}

@Composable
private fun StorageAccessCard(onRequest: () -> Unit) {
    SectionCard(stringResource(R.string.limited_file_access)) {
        Text(
            stringResource(R.string.limited_file_access_body) +
                stringResource(R.string.limited_file_access_body2),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onRequest) { Text(stringResource(R.string.grant_all_files)) }
    }
}

@Composable
private fun SendCard(
    state: MainViewModel.UiState,
    viewModel: MainViewModel,
    onPickFiles: () -> Unit,
    onScanCode: () -> Unit,
) {
    var manualHost by rememberSaveable { mutableStateOf("") }

    SectionCard(stringResource(R.string.send_to_a_pc)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { viewModel.scanForPeers() }, enabled = !state.scanning) {
                Text(if (state.scanning) stringResource(R.string.scanning) else stringResource(R.string.scan_the_lan))
            }
            Spacer(Modifier.width(12.dp))
            if (state.scanning) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp))
        }

        // The fastest path there is: one scan replaces the address, the port and the token.
        FilledTonalButton(onClick = onScanCode, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.scan_to_connect))
        }

        if (state.peers.isEmpty()) {
            Text(
                stringResource(R.string.run_afmu_serve),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.peers.forEach { peer ->
            PeerRow(
                peer = peer,
                selected = peer == state.selectedPeer,
                canAsk = state.outgoingAuth == null,
                onSelect = { viewModel.selectPeer(peer) },
                onAsk = { viewModel.requestAuthorization(peer) },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = manualHost,
                onValueChange = { manualHost = it },
                label = { Text(stringResource(R.string.pc_address_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    viewModel.addPeerManually(manualHost)
                    manualHost = ""
                },
                enabled = manualHost.isNotBlank(),
            ) { Text(stringResource(R.string.add)) }
        }

        OutlinedTextField(
            value = state.peerToken,
            onValueChange = { viewModel.setPeerToken(it) },
            label = { Text(stringResource(R.string.pc_token_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = onPickFiles,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.selectedPeer != null || state.peers.isNotEmpty(),
        ) { Text(stringResource(R.string.choose_files_and_send)) }

        OutlinedButton(
            onClick = { viewModel.openRemoteBrowser() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.scanning,
        ) { Text(stringResource(R.string.browse_pc_and_pull)) }

        if (state.transfers.isNotEmpty()) {
            HorizontalDivider()
            state.transfers.forEach { TransferRow(it) }
            TextButton(onClick = { viewModel.clearFinishedTransfers() }) { Text(stringResource(R.string.clear_finished)) }
        }
    }
}

@Composable
private fun PeerRow(
    peer: Discovery.Peer,
    selected: Boolean,
    canAsk: Boolean,
    onSelect: () -> Unit,
    onAsk: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(peer.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${peer.host}:${peer.port}  ·  ${peer.os}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Nobody wants to read a token off another phone's screen and type it in here.
        TextButton(onClick = onAsk, enabled = canAsk) {
            Text(stringResource(R.string.ask_to_connect))
        }
    }
}

/**
 * The other half of [AuthRequestDialog]: we are the ones asking. The code shown here is the
 * one the other device must be displaying — that is what tells the person over there that the
 * prompt they are looking at is this request and not somebody else's.
 */
@Composable
private fun OutgoingAuthDialog(pending: MainViewModel.OutgoingAuth, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.outgoing_auth_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (pending.sending) stringResource(R.string.outgoing_auth_sending)
                    else stringResource(R.string.outgoing_auth_body, pending.peer.name),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    pending.code,
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.outgoing_auth_code_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.outgoing_auth_remaining, pending.remaining),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun TransferRow(transfer: MainViewModel.Transfer) {
    val arrow = if (transfer.direction == MainViewModel.Direction.SEND) "↑" else "↓"
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row {
            Text(
                "$arrow  ${transfer.name}",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                when (transfer.status) {
                    MainViewModel.Status.DONE -> stringResource(R.string.state_done)
                    MainViewModel.Status.FAILED -> stringResource(R.string.state_failed)
                    MainViewModel.Status.RUNNING ->
                        if (transfer.total > 0) "${(transfer.fraction * 100).toInt()}%"
                        else HttpServer.formatBytes(transfer.moved)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (transfer.status == MainViewModel.Status.FAILED)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (transfer.status == MainViewModel.Status.RUNNING) {
            LinearProgressIndicator(
                progress = { transfer.fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        if (transfer.detail.isNotEmpty()) {
            Text(
                transfer.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ------------------------------------------------------------------- remote file browser

/**
 * Full-screen view of the PC's file tree. Tapping a folder walks into it, tapping a file
 * pulls it into this phone's inbox; running transfers stay visible along the bottom so the
 * user does not have to close the browser to watch progress.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteBrowser(
    browse: MainViewModel.RemoteBrowse,
    transfers: List<MainViewModel.Transfer>,
    viewModel: MainViewModel,
) {
    Dialog(
        onDismissRequest = { viewModel.closeRemoteBrowser() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(browse.peer.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${browse.peer.host}:${browse.peer.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { viewModel.closeRemoteBrowser() }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                            }
                        },
                        actions = {
                            IconButton(onClick = { viewModel.reloadRemote() }) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.reload))
                            }
                        },
                    )
                },
                bottomBar = { BrowserTransferBar(transfers, viewModel) },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { viewModel.browseRemote(browse.parent ?: "/") },
                            enabled = browse.parent != null || browse.path != "/",
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.up_one_folder))
                        }
                        Text(
                            browse.path,
                            modifier = Modifier.weight(1f),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = { viewModel.receiveAllInFolder() },
                            enabled = browse.files.isNotEmpty(),
                        ) { Text(stringResource(R.string.pull_all, browse.files.size)) }
                    }

                    if (browse.loading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    browse.error?.let { error ->
                        Text(
                            error,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!browse.loading && browse.error == null && browse.entries.isEmpty()) {
                        Text(
                            stringResource(R.string.this_folder_is_empty),
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    HorizontalDivider()
                    LazyColumn(Modifier.weight(1f)) {
                        items(browse.entries, key = { it.path }) { entry ->
                            RemoteEntryRow(entry) {
                                if (entry.isDir) viewModel.browseRemote(entry.path)
                                else viewModel.receiveFromPeer(entry)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteEntryRow(entry: MainViewModel.RemoteEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.isDir) Icons.Default.Folder else Icons.Default.Download,
            contentDescription = if (entry.isDir) stringResource(R.string.folder) else stringResource(R.string.download),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (if (entry.isDir) "folder" else HttpServer.formatBytes(entry.size)) +
                    formatMtime(entry.mtime),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Protocol mtimes are Unix *seconds*; 0 means the peer did not report one. */
private fun formatMtime(seconds: Long): String {
    if (seconds <= 0) return ""
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return "  ·  " + format.format(Date(seconds * 1000))
}

@Composable
private fun BrowserTransferBar(
    transfers: List<MainViewModel.Transfer>,
    viewModel: MainViewModel,
) {
    val incoming = transfers.filter { it.direction == MainViewModel.Direction.RECEIVE }
    if (incoming.isEmpty()) return

    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            incoming.forEach { TransferRow(it) }
            if (incoming.any { it.status != MainViewModel.Status.RUNNING }) {
                TextButton(onClick = { viewModel.clearFinishedTransfers() }) {
                    Text(stringResource(R.string.clear_finished))
                }
            }
        }
    }
}

/**
 * The v2 pairing table (PROTOCOL-v2-DRAFT.md §4.3).
 *
 * Nothing writes to it yet — that waits on the mTLS handshake in §12 steps 3–6. The list and
 * its delete action ship first deliberately: in v2 a row here *is* an open door, and a door
 * that can be opened before it can be closed is the wrong order to build things in.
 */
@Composable
private fun PairedDevicesCard(
    state: MainViewModel.UiState,
    viewModel: MainViewModel,
    onCopy: (String) -> Unit,
) {
    var pendingUnpair by remember { mutableStateOf<PeerRecord?>(null) }

    SectionCard(stringResource(R.string.paired_devices)) {
        if (state.pairedPeers.isEmpty()) {
            Text(
                stringResource(R.string.paired_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.pairedPeers.forEach { peer ->
                PairedDeviceRow(
                    peer = peer,
                    onCopy = onCopy,
                    onUnpair = { pendingUnpair = peer },
                )
            }
            Text(
                stringResource(R.string.paired_no_expiry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // A list quietly shorter than what is stored is how "wasn't that phone paired?" starts.
        if (state.pairedDropped > 0) {
            Text(
                stringResource(R.string.paired_dropped, state.pairedDropped),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    pendingUnpair?.let { peer ->
        AlertDialog(
            onDismissRequest = { pendingUnpair = null },
            title = { Text(stringResource(R.string.unpair)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.unpair_confirm))
                    if (peer.name.isNotEmpty()) Text(peer.name)
                    Text(
                        Base32.group(peer.fp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unpair(peer.fp)
                    pendingUnpair = null
                }) { Text(stringResource(R.string.unpair)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnpair = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun PairedDeviceRow(
    peer: PeerRecord,
    onCopy: (String) -> Unit,
    onUnpair: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    peer.name.ifEmpty { stringResource(R.string.paired_unnamed) },
                    style = MaterialTheme.typography.bodyLarge,
                )
                // Talks v2 only; never falls back to plaintext (draft §8.1).
                if (peer.pinned) {
                    Text(
                        stringResource(R.string.paired_encrypted_only),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // The fingerprint *is* this device's identity, so it is shown in full: the user
            // compares it against the other screen, and a truncated one hides the mismatch.
            Text(
                Base32.group(peer.fp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val detail = listOfNotNull(
                peer.pairedAt.takeIf { it > 0 }
                    ?.let { stringResource(R.string.paired_since, formatMtime(it)) },
                peer.lastHost.takeIf { it.isNotEmpty() }
                    ?.let { stringResource(R.string.paired_last_seen, "$it:${peer.lastPort}") },
            ).joinToString("  ·  ")
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = { onCopy(Base32.group(peer.fp)) }) {
            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy_fingerprint))
        }
        IconButton(onClick = onUnpair) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.unpair),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SettingsCard(state: MainViewModel.UiState, viewModel: MainViewModel) {
    var name by rememberSaveable(state.deviceName) { mutableStateOf(state.deviceName) }

    SectionCard(stringResource(R.string.settings)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.device_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = { viewModel.setDeviceName(name) },
            enabled = name != state.deviceName,
        ) { Text(stringResource(R.string.save_name)) }

        ToggleRow(
            title = stringResource(R.string.discoverable),
            subtitle = stringResource(R.string.discoverable_desc),
            checked = state.discoverable,
            onChange = { viewModel.setDiscoverable(it) },
        )
        ToggleRow(
            title = stringResource(R.string.allow_writes),
            subtitle = stringResource(R.string.allow_writes_desc),
            checked = state.writable,
            onChange = { viewModel.setWritable(it) },
        )
        ToggleRow(
            title = stringResource(R.string.allow_auth_requests),
            subtitle = stringResource(R.string.allow_auth_requests_desc),
            checked = state.allowAuthRequests,
            onChange = { viewModel.setAllowAuthRequests(it) },
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        LanguageRow(state.language) { viewModel.setLanguage(it) }
    }
}

/**
 * Language picker. Picking one re-resolves every string in place; nothing is rebuilt.
 *
 * FlowRow, not Row: the labels are the language names themselves, so the widest row is whichever
 * language is showing. "Follow system" is nearly twice as wide as "跟随系统", and in a plain Row
 * that overflow squeezed the last option down to a sliver at the screen edge — visibly there,
 * effectively untappable. Wrapping to a second line costs nothing and never hides an option.
 */
@Composable
private fun LanguageRow(current: String, onPick: (String) -> Unit) {
    Text(
        stringResource(R.string.language),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val options = listOf(
            Prefs.LANG_SYSTEM to stringResource(R.string.language_system),
            Prefs.LANG_ENGLISH to stringResource(R.string.language_en),
            Prefs.LANG_CHINESE to stringResource(R.string.language_zh),
        )
        options.forEach { (tag, label) ->
            if (tag == current) {
                Button(onClick = {}) { Text(label) }
            } else {
                OutlinedButton(onClick = { onPick(tag) }) { Text(label) }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LogCard(state: MainViewModel.UiState, viewModel: MainViewModel) {
    SectionCard(stringResource(R.string.activity)) {
        if (state.log.isEmpty()) {
            Text(
                stringResource(R.string.nothing_yet),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                state.log.asReversed().forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Row {
                TextButton(onClick = { viewModel.clearLog() }) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.clear))
                }
            }
        }
    }
}
