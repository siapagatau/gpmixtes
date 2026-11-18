@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gpaudio.gpmix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpaudio.gpmix.MixerViewModel
import com.gpaudio.gpmix.R

private val PadKeys = listOf(
    Key.One, Key.Two, Key.Three, Key.Four, Key.Q, Key.W, Key.E, Key.R,
    Key.A, Key.S, Key.D, Key.F, Key.Z, Key.X, Key.C, Key.V,
)

@Composable
fun GpMixApp(
    vm: MixerViewModel,
    onToggleEngine: () -> Unit,
    media: MediaActions,
    settings: SettingsActions,
    onSavePreset: (String) -> Unit,
    onLoadPreset: () -> Unit,
) {
    GpTheme(vm.theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!vm.engineReady) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text(stringResource(R.string.engine_error_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.engine_error_text, vm.engineError ?: "?"))
                }
                return@Surface
            }
            Content(vm, onToggleEngine, media, settings, onSavePreset, onLoadPreset)
        }
    }
}

@Composable
private fun Content(
    vm: MixerViewModel,
    onToggleEngine: () -> Unit,
    media: MediaActions,
    settings: SettingsActions,
    onSavePreset: (String) -> Unit,
    onLoadPreset: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val snack = remember { SnackbarHostState() }
    val focus = remember { FocusRequester() }

    // Pesan sekali-tampil dari ViewModel.
    val msg = vm.message
    if (msg != null) {
        val text = if (msg.arg != null) stringResource(msg.resId, msg.arg) else stringResource(msg.resId)
        LaunchedEffect(msg) {
            snack.showSnackbar(text)
            vm.dismissMessage()
        }
    }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) { } }

    var donation by remember { mutableStateOf(!vm.prefs.donationSeen) }

    Scaffold(
        modifier = Modifier
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (e.isCtrlPressed || e.isMetaPressed) {
                    when (e.key) {
                        Key.Z -> { if (e.isShiftPressed) vm.redo() else vm.undo(); true }
                        Key.Y -> { vm.redo(); true }
                        Key.S -> { vm.saveNow(); true }
                        else -> false
                    }
                } else if (!e.isAltPressed) {
                    val i = PadKeys.indexOf(e.key)
                    if (i >= 0 && tab == 1) { vm.triggerPad(i); true } else false
                } else false
            },
        snackbarHost = { SnackbarHost(snack) },
        topBar = { TopRow(vm, onToggleEngine) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("🎚") }, label = { Text(stringResource(R.string.tab_mixer)) })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("🥁") }, label = { Text(stringResource(R.string.tab_pads)) })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("⚙") }, label = { Text(stringResource(R.string.tab_settings)) })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> MixerScreen(vm)
                1 -> MediaScreen(vm, media)
                else -> SettingsScreen(vm, settings)
            }
        }
    }

    vm.selectedChannel?.let { id -> ChannelSheet(vm, id) }
    vm.editingInsert?.let { (id, slot) -> EffectEditor(vm, id, slot, onSavePreset, onLoadPreset) }

    if (donation) {
        AlertDialog(
            onDismissRequest = { donation = false; vm.prefs.donationSeen = true },
            title = { Text(stringResource(R.string.app_name)) },
            text = { Text(stringResource(R.string.donation_text)) },
            confirmButton = {
                TextButton(onClick = { donation = false; vm.prefs.donationSeen = true }) { Text(stringResource(R.string.donation_done)) }
            },
            dismissButton = {
                if (stringResource(R.string.donate_url).isNotBlank()) {
                    TextButton(onClick = { settings.openDonate(); donation = false; vm.prefs.donationSeen = true }) { Text(stringResource(R.string.donate)) }
                }
            },
        )
    }
}

@Composable
private fun TopRow(vm: MixerViewModel, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name) + " " + vm.limits.name, style = MaterialTheme.typography.titleMedium)
            val i = vm.info
            Text(
                if (vm.running && i.sampleRate > 0) stringResource(R.string.top_status, vm.project.name, i.latencyMs, i.xruns)
                else vm.project.name,
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
        if (vm.captureActive) Text("APP", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
        if (vm.recording) Text("● REC", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        Button(onClick = onToggle) { Text(stringResource(if (vm.running) R.string.stop else R.string.start)) }
    }
}
