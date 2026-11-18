@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gpaudio.gpmix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpaudio.gpmix.MixerViewModel
import com.gpaudio.gpmix.MidiTarget
import com.gpaudio.gpmix.R
import com.gpaudio.gpmix.model.ChKind
import com.gpaudio.gpmix.model.FaderMap
import java.io.File

/** Tombol keyboard fisik untuk 16 pad: baris 1234 / QWER / ASDF / ZXCV. */
val PadKeyLabels = listOf("1", "2", "3", "4", "Q", "W", "E", "R", "A", "S", "D", "F", "Z", "X", "C", "V")

class MediaActions(
    val pickPad: (Int) -> Unit,
    val pickPlayer: () -> Unit,
    val saveRecording: (File) -> Unit,
)

@Composable
fun MediaScreen(vm: MixerViewModel, actions: MediaActions, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf<Int?>(null) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.pads))
        PadGrid(vm, onEdit = { editing = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.stopPads() }) { Text(stringResource(R.string.pads_stop)) }
            Text(stringResource(R.string.pads_master), fontSize = 12.sp)
            GpSlider(vm.project.settings.padsMasterDb, { v -> vm.change { it.copy(settings = it.settings.copy(padsMasterDb = v)) } },
                { vm.beginGesture() }, stringResource(R.string.pads_master), FaderMap.format(vm.project.settings.padsMasterDb) + " dB",
                Modifier.weight(1f), -60f..6f)
        }
        Text(stringResource(R.string.pads_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PlayerCard(vm, actions)
        RecorderCard(vm, actions)
    }
    editing?.let { PadEditor(vm, it, actions, onClose = { editing = null }) }
}

@Composable
private fun PadGrid(vm: MixerViewModel, onEdit: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (r in 0 until 4) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (cIdx in 0 until 4) {
                    val i = r * 4 + cIdx
                    Box(Modifier.weight(1f)) { PadButton(vm, i, onEdit) }
                }
            }
        }
    }
}

@Composable
private fun PadButton(vm: MixerViewModel, i: Int, onEdit: (Int) -> Unit) {
    val cfg = vm.project.pads.getOrNull(i)
    val playing = vm.padPlaying and (1 shl i) != 0
    val empty = cfg?.file == null
    val bg = when {
        playing -> MaterialTheme.colorScheme.primary
        empty -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (playing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val name = cfg?.name?.ifEmpty { null } ?: stringResource(R.string.pad_empty)
    val desc = stringResource(R.string.cd_pad, i + 1, name)
    val tapLabel = stringResource(R.string.pad_trigger)
    val longLabel = stringResource(R.string.pad_edit)
    val learning = vm.midiLearn == MidiTarget.PAD && vm.midiLearnArg == i
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(bg)
            .semantics(mergeDescendants = true) {
                contentDescription = desc
                onClick(tapLabel) { vm.triggerPad(i); true }
                onLongClick(longLabel) { onEdit(i); true }
            }
            .pointerInput(i, empty) {
                detectTapGestures(
                    onPress = { if (!empty) vm.triggerPad(i); tryAwaitRelease() },
                    onLongPress = { onEdit(i) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(2.dp)) {
            Text(PadKeyLabels[i], fontSize = 11.sp, color = fg.copy(alpha = 0.7f))
            Text(if (learning) "MIDI…" else name, fontSize = 12.sp, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PadEditor(vm: MixerViewModel, i: Int, actions: MediaActions, onClose: () -> Unit) {
    val cfg = vm.project.pads.getOrNull(i) ?: return
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.pad_title, i + 1)) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(cfg.name.ifEmpty { stringResource(R.string.pad_empty) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { actions.pickPad(i) }, enabled = vm.padLoading == null) {
                        Text(stringResource(if (vm.padLoading == i) R.string.loading else R.string.pad_load))
                    }
                    OutlinedButton(onClick = { vm.clearPad(i) }, enabled = cfg.file != null) { Text(stringResource(R.string.pad_clear)) }
                }
                Text(stringResource(R.string.pad_gain), fontSize = 12.sp)
                GpSlider(cfg.gainDb, { v -> vm.editPad(i, false) { it.copy(gainDb = v) } }, { vm.beginGesture() }, stringResource(R.string.pad_gain), FaderMap.format(cfg.gainDb) + " dB", range = -24f..24f)
                Text(stringResource(R.string.pad_start) + " " + (cfg.start * 100).toInt() + "%", fontSize = 12.sp)
                GpSlider(cfg.start, { v -> vm.editPad(i, false) { it.copy(start = v.coerceAtMost(it.end - 0.01f)) } }, { vm.beginGesture() }, stringResource(R.string.pad_start), "${(cfg.start * 100).toInt()}%")
                Text(stringResource(R.string.pad_end) + " " + (cfg.end * 100).toInt() + "%", fontSize = 12.sp)
                GpSlider(cfg.end, { v -> vm.editPad(i, false) { it.copy(end = v.coerceAtLeast(it.start + 0.01f)) } }, { vm.beginGesture() }, stringResource(R.string.pad_end), "${(cfg.end * 100).toInt()}%")
                Text(stringResource(R.string.pad_choke), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    for (g in 0..8) {
                        ToggleChip(if (g == 0) "–" else g.toString(), cfg.choke == g, { vm.editPad(i) { it.copy(choke = g) } },
                            if (g == 0) stringResource(R.string.choke_off) else stringResource(R.string.choke_group, g), Modifier.weight(1f))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pad_normalize))
                    Switch(cfg.normalize, { on -> vm.editPad(i) { it.copy(normalize = on) } })
                }
                if (vm.midiLearn == MidiTarget.PAD && vm.midiLearnArg == i) {
                    Text(stringResource(R.string.midi_waiting), color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { vm.cancelMidiLearn() }) { Text(stringResource(R.string.cancel)) }
                } else {
                    OutlinedButton(onClick = { vm.startMidiLearn(MidiTarget.PAD, i) }) { Text(stringResource(R.string.midi_learn)) }
                }
            }
        },
    )
}

private fun fmtTime(frames: Long, rate: Int): String {
    val s = if (rate > 0) (frames / rate).toInt() else 0
    return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
}

@Composable
private fun PlayerCard(vm: MixerViewModel, actions: MediaActions) {
    SectionTitle(stringResource(R.string.player))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(vm.playerTitle ?: stringResource(R.string.player_empty), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (vm.playerFrames > 0) {
            val frac = if (vm.playerFrames > 0) vm.playerPos.toFloat() / vm.playerFrames else 0f
            GpSlider(frac, { v -> vm.seekPlayer(v) }, { }, stringResource(R.string.player_seek),
                fmtTime(vm.playerPos, vm.playerRate) + " / " + fmtTime(vm.playerFrames, vm.playerRate))
            Text(fmtTime(vm.playerPos, vm.playerRate) + " / " + fmtTime(vm.playerFrames, vm.playerRate), fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = actions.pickPlayer, enabled = !vm.playerBusy) {
                Text(stringResource(if (vm.playerBusy) R.string.loading else R.string.player_load))
            }
            OutlinedButton(onClick = { vm.togglePlay() }, enabled = vm.playerFrames > 0) {
                Text(stringResource(if (vm.playerPlaying) R.string.pause else R.string.play))
            }
            ToggleChip(stringResource(R.string.loop), vm.playerLoop, { vm.updatePlayerLoop(!vm.playerLoop) }, stringResource(R.string.loop))
            if (vm.playerFrames > 0) TextButton(onClick = { vm.clearPlayer() }) { Text(stringResource(R.string.pad_clear)) }
        }
        Text(stringResource(R.string.player_volume), fontSize = 12.sp)
        GpSlider(vm.playerVolumeDb, { v -> vm.setPlayerVolume(v) }, { }, stringResource(R.string.player_volume),
            FaderMap.format(vm.playerVolumeDb) + " dB", range = -60f..6f)
        Text(stringResource(R.string.player_hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecorderCard(vm: MixerViewModel, actions: MediaActions) {
    SectionTitle(stringResource(R.string.recorder))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        var open by remember { mutableStateOf(false) }
        val scene = vm.project.scene
        val srcName = if (vm.recSource < 0) stringResource(R.string.kind_master) else scene.channel(vm.recSource)?.name ?: stringResource(R.string.kind_master)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.rec_source))
            Box {
                OutlinedButton(onClick = { open = true }, enabled = !vm.recording) { Text(srcName) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.kind_master)) }, onClick = { open = false; vm.recSource = -1 })
                    scene.channels.filter { it.kind != ChKind.MASTER }.forEach { c ->
                        DropdownMenuItem(text = { Text(c.name) }, onClick = { open = false; vm.recSource = c.id })
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { if (vm.recording) vm.stopRecording() else vm.startRecording() }) {
                Text(stringResource(if (vm.recording) R.string.rec_stop else R.string.rec_start))
            }
            if (vm.recording) Text(String.format(java.util.Locale.US, "%d:%02d", vm.recSeconds.toInt() / 60, vm.recSeconds.toInt() % 60), color = MaterialTheme.colorScheme.error)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.retro_enable))
            Switch(vm.project.settings.retroRecord, { on -> vm.setSettings { it.copy(retroRecord = on) } })
        }
        Text(stringResource(R.string.retro_hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { vm.saveRetro() }, enabled = vm.project.settings.retroRecord && vm.running) { Text(stringResource(R.string.retro_save)) }
        if (vm.recordings.isNotEmpty()) {
            Text(stringResource(R.string.recordings), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            vm.recordings.take(20).forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(f.name, Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { actions.saveRecording(f) }) { Text(stringResource(R.string.save_as)) }
                    TextButton(onClick = { vm.deleteRecording(f) }) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }
}
