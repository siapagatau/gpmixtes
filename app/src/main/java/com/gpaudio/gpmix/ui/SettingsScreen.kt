@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gpaudio.gpmix.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpaudio.gpmix.BuildConfig
import com.gpaudio.gpmix.MidiTarget
import com.gpaudio.gpmix.MixerViewModel
import com.gpaudio.gpmix.R
import com.gpaudio.gpmix.model.ChKind

class SettingsActions(
    val exportProject: () -> Unit,
    val importProject: () -> Unit,
    val startCapture: () -> Unit,
    val setLanguage: (String) -> Unit,
    val setTheme: (String) -> Unit,
    val openDonate: () -> Unit,
)

@Composable
fun SettingsScreen(vm: MixerViewModel, actions: SettingsActions, modifier: Modifier = Modifier) {
    var dialog by remember { mutableStateOf<String?>(null) }
    val s = vm.project.settings
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ---- Audio ----
        SectionTitle(stringResource(R.string.audio_setup))
        DevicePicker(stringResource(R.string.input_device), vm.inputDevices.map { it.id to it.name }, vm.inputDeviceId) { vm.setInputDevice(it) }
        DevicePicker(stringResource(R.string.output_device), vm.outputDevices.map { it.id to it.name }, vm.outputDeviceId) { vm.setOutputDevice(it) }
        OutlinedButton(onClick = { vm.refreshDevices() }) { Text(stringResource(R.string.refresh_devices)) }
        Text(stringResource(R.string.input_channels), fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            for (n in 1..8) ToggleChip(n.toString(), s.inputChannels == n, { vm.setSettings { it.copy(inputChannels = n) } }, stringResource(R.string.cd_input_channels, n), Modifier.weight(1f))
        }
        Text(stringResource(R.string.buffer_size, s.bufferMultiplier), fontSize = 13.sp)
        GpSlider(s.bufferMultiplier.toFloat(), { v -> vm.setSettings { it.copy(bufferMultiplier = Math.round(v).coerceIn(2, 8)) } }, { },
            stringResource(R.string.buffer_size, s.bufferMultiplier), "${s.bufferMultiplier}", range = 2f..8f)
        Text(stringResource(R.string.buffer_hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val i = vm.info
        if (vm.running && i.sampleRate > 0) {
            Text(stringResource(R.string.engine_info, i.sampleRate, i.burst, i.bufferFrames, i.latencyMs, i.xruns), fontSize = 12.sp)
        } else {
            Text(stringResource(R.string.engine_stopped), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.latency_hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // ---- Mix ----
        SectionTitle(stringResource(R.string.mix_settings))
        Text(stringResource(R.string.tempo, s.tempo.toInt()), fontSize = 13.sp)
        GpSlider(s.tempo, { v -> vm.change { it.copy(settings = it.settings.copy(tempo = Math.round(v).toFloat())) } }, { vm.beginGesture() },
            stringResource(R.string.tempo, s.tempo.toInt()), "${s.tempo.toInt()}", range = 40f..240f)
        SwitchRow(stringResource(R.string.ducking_enable), s.duckingEnabled) { on -> vm.setSettings { it.copy(duckingEnabled = on) } }
        SwitchRow(stringResource(R.string.master_mono), s.masterMono) { on -> vm.setSettings { it.copy(masterMono = on) } }
        Text(stringResource(R.string.pan_law), fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (d in listOf(-3f, -4.5f, -6f)) {
                ToggleChip("${d} dB", s.panLawDb == d, { vm.setSettings { it.copy(panLawDb = d) } }, stringResource(R.string.pan_law) + " $d dB")
            }
        }

        // ---- Audio aplikasi ----
        SectionTitle(stringResource(R.string.app_audio))
        Text(stringResource(R.string.app_audio_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (Build.VERSION.SDK_INT >= 29) {
            OutlinedButton(onClick = { if (vm.captureActive) vm.stopCapture() else actions.startCapture() }, enabled = vm.running) {
                Text(stringResource(if (vm.captureActive) R.string.capture_stop else R.string.capture_start))
            }
            if (!vm.running) Text(stringResource(R.string.msg_start_first), fontSize = 11.sp)
        } else {
            Text(stringResource(R.string.capture_unsupported), fontSize = 12.sp)
        }

        // ---- MIDI ----
        SectionTitle(stringResource(R.string.midi))
        Text(
            if (vm.midiDevices.isEmpty()) stringResource(R.string.midi_none) else vm.midiDevices.joinToString(", "),
            fontSize = 12.sp,
        )
        FaderLearn(vm)
        Text(stringResource(R.string.midi_mappings, vm.midiMappingCount), fontSize = 12.sp)
        OutlinedButton(onClick = { vm.clearMidiMap() }) { Text(stringResource(R.string.midi_clear)) }

        // ---- Proyek ----
        SectionTitle(stringResource(R.string.project))
        Text(stringResource(R.string.project_current, vm.project.name))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.undo() }, enabled = vm.canUndo) { Text(stringResource(R.string.undo)) }
            OutlinedButton(onClick = { vm.redo() }, enabled = vm.canRedo) { Text(stringResource(R.string.redo)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { dialog = "newproject" }) { Text(stringResource(R.string.project_new)) }
            OutlinedButton(onClick = { vm.refreshProjectList(); dialog = "open" }) { Text(stringResource(R.string.project_open)) }
            OutlinedButton(onClick = { dialog = "renameproject" }) { Text(stringResource(R.string.scene_rename)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = actions.exportProject) { Text(stringResource(R.string.project_export)) }
            OutlinedButton(onClick = actions.importProject) { Text(stringResource(R.string.project_import)) }
        }
        OutlinedButton(onClick = { dialog = "reset" }) { Text(stringResource(R.string.project_reset)) }

        // ---- Tampilan ----
        SectionTitle(stringResource(R.string.appearance))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleChip(stringResource(R.string.theme_default), vm.theme != "studio", { actions.setTheme("default") }, stringResource(R.string.theme_default))
            ToggleChip(stringResource(R.string.theme_studio), vm.theme == "studio", { actions.setTheme("studio") }, stringResource(R.string.theme_studio))
        }
        Text(stringResource(R.string.language), fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleChip(stringResource(R.string.lang_system), vm.prefs.language == "system", { actions.setLanguage("system") }, stringResource(R.string.lang_system))
            ToggleChip("English", vm.prefs.language == "en", { actions.setLanguage("en") }, "English")
            ToggleChip("Indonesia", vm.prefs.language == "id", { actions.setLanguage("id") }, "Bahasa Indonesia")
        }

        // ---- Info ----
        SectionTitle(stringResource(R.string.info))
        Text(stringResource(R.string.shortcuts_text), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { dialog = "about" }) { Text(stringResource(R.string.about)) }
            OutlinedButton(onClick = { dialog = "licenses" }) { Text(stringResource(R.string.licenses)) }
            OutlinedButton(onClick = { dialog = "limits" }) { Text(stringResource(R.string.android_limits)) }
        }
    }

    when (dialog) {
        "newproject", "renameproject" -> {
            val isNew = dialog == "newproject"
            var name by remember { mutableStateOf(if (isNew) "" else vm.project.name) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(if (isNew) R.string.project_new else R.string.scene_rename)) },
                text = { OutlinedTextField(name, { name = it.take(60) }, singleLine = true, label = { Text(stringResource(R.string.name)) }) },
                confirmButton = { TextButton(onClick = { if (isNew) vm.newProject(name) else vm.renameProject(name); dialog = null }) { Text(stringResource(R.string.ok)) } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        "open" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.project_open)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (vm.projectFiles.isEmpty()) Text(stringResource(R.string.project_none))
                    vm.projectFiles.forEach { n ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { vm.openProject(n); dialog = null }, modifier = Modifier.weight(1f)) { Text(n, maxLines = 1) }
                            TextButton(onClick = { vm.deleteProject(n) }, enabled = n != vm.project.name) { Text(stringResource(R.string.delete)) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.close)) } },
        )
        "reset" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.project_reset)) },
            text = { Text(stringResource(R.string.project_reset_confirm)) },
            confirmButton = { TextButton(onClick = { vm.resetToDefault(); dialog = null }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
        "about" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.app_name)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.about_text, BuildConfig.VERSION_NAME, vm.limits.name))
                    if (stringResource(R.string.donate_url).isNotBlank()) {
                        OutlinedButton(onClick = actions.openDonate, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.donate)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.close)) } },
        )
        "limits" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.android_limits)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(R.string.android_limits_text)) } },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.close)) } },
        )
        "licenses" -> {
            val ctx = LocalContext.current
            val text = remember {
                try { ctx.resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() } } catch (e: Exception) { "" }
            }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.licenses)) },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text, fontSize = 11.sp) } },
                confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.close)) } },
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Switch(on, onChange)
    }
}

@Composable
private fun DevicePicker(label: String, items: List<Pair<Int, String>>, selected: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cur = items.firstOrNull { it.first == selected }?.second ?: stringResource(R.string.device_default)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 13.sp)
        Box {
            OutlinedButton(onClick = { open = true }) { Text(cur, maxLines = 1) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.device_default)) }, onClick = { open = false; onPick(0) })
                items.forEach { (id, n) -> DropdownMenuItem(text = { Text(n) }, onClick = { open = false; onPick(id) }) }
            }
        }
    }
}

@Composable
private fun FaderLearn(vm: MixerViewModel) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.midi_learn_fader), fontSize = 13.sp)
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(if (vm.midiLearn == MidiTarget.FADER) stringResource(R.string.midi_waiting) else stringResource(R.string.midi_pick_channel))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                vm.project.scene.channels.forEach { c ->
                    DropdownMenuItem(text = { Text(c.name) }, onClick = { open = false; vm.startMidiLearn(MidiTarget.FADER, c.id) })
                }
            }
        }
        if (vm.midiLearn == MidiTarget.FADER) TextButton(onClick = { vm.cancelMidiLearn() }) { Text(stringResource(R.string.cancel)) }
    }
}
