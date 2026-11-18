@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gpaudio.gpmix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpaudio.gpmix.MixerViewModel
import com.gpaudio.gpmix.R
import com.gpaudio.gpmix.audio.NativeEngine as N
import com.gpaudio.gpmix.model.ChKind
import com.gpaudio.gpmix.model.Channel
import com.gpaudio.gpmix.model.FaderMap

@Composable
fun MixerScreen(vm: MixerViewModel, modifier: Modifier = Modifier) {
    val scene = vm.project.scene
    Column(modifier.fillMaxSize()) {
        SceneBar(vm)
        val master = scene.master
        val others = scene.channels.filter { it.kind != ChKind.MASTER }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            LazyRow(
                Modifier.weight(1f).fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                items(others, key = { it.id }) { c -> ChannelStrip(vm, c) }
                item(key = "add") { AddChannelColumn(vm) }
            }
            if (master != null) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant))
                Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { ChannelStrip(vm, master) }
            }
        }
    }
}

@Composable
private fun SceneBar(vm: MixerViewModel) {
    val p = vm.project
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.scene), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        p.scenes.forEachIndexed { i, s ->
            ToggleChip(
                text = s.name, on = i == p.activeScene, onClick = { vm.selectScene(i) },
                desc = stringResource(R.string.cd_scene, s.name), modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        Box {
            OutlinedButton(onClick = { menu = true }) { Text(stringResource(R.string.scene_menu)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.scene_new)) }, onClick = { menu = false; dialog = "new" })
                DropdownMenuItem(text = { Text(stringResource(R.string.scene_duplicate)) }, onClick = { menu = false; vm.duplicateScene() })
                DropdownMenuItem(text = { Text(stringResource(R.string.scene_rename)) }, onClick = { menu = false; dialog = "rename" })
                DropdownMenuItem(text = { Text(stringResource(R.string.scene_delete)) }, onClick = { menu = false; dialog = "delete" })
            }
        }
    }
    when (dialog) {
        "new", "rename" -> {
            var name by remember { mutableStateOf(if (dialog == "rename") vm.project.scene.name else "") }
            val isNew = dialog == "new"
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(if (isNew) R.string.scene_new else R.string.scene_rename)) },
                text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text(stringResource(R.string.name)) }) },
                confirmButton = {
                    TextButton(onClick = { if (isNew) vm.addScene(name) else vm.renameScene(name); dialog = null }) { Text(stringResource(R.string.ok)) }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.scene_delete)) },
            text = { Text(stringResource(R.string.scene_delete_confirm, vm.project.scene.name)) },
            confirmButton = { TextButton(onClick = { vm.deleteScene(); dialog = null }, enabled = vm.project.scenes.size > 1) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun AddChannelColumn(vm: MixerViewModel) {
    Column(
        Modifier.width(110.dp).fillMaxHeight().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.add_channel), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { vm.addChannel(ChKind.AUDIO) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.kind_input), maxLines = 1) }
        OutlinedButton(onClick = { vm.addChannel(ChKind.AUX) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.kind_aux), maxLines = 1) }
        OutlinedButton(onClick = { vm.addChannel(ChKind.GROUP) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.kind_group), maxLines = 1) }
        Text(
            stringResource(R.string.edition_limits, vm.limits.name, vm.project.scene.count(ChKind.AUDIO), vm.limits.audioInputs),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ChannelStrip(vm: MixerViewModel, c: Channel) {
    val accent = ChannelColors[c.color.coerceIn(0, 7)]
    val selected = vm.selectedChannel == c.id
    val kindLabel = when (c.kind) {
        ChKind.AUDIO -> stringResource(R.string.kind_input)
        ChKind.AUX -> stringResource(R.string.kind_aux)
        ChKind.GROUP -> stringResource(R.string.kind_group)
        ChKind.MASTER -> stringResource(R.string.kind_master)
    }
    val shape = RoundedCornerShape(10.dp)
    val openDesc = stringResource(R.string.cd_open_channel, c.name, kindLabel)
    val panDesc = stringResource(R.string.cd_pan, c.name)
    Column(
        Modifier
            .width(92.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // kepala: warna + nama + jenis; ketuk untuk membuka pengaturan channel
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { vm.selectedChannel = c.id }
                .semantics { contentDescription = openDesc }
                .padding(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(accent))
            Text(c.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                kindLabel + if (c.inserts.isNotEmpty()) " · FX${c.inserts.size}" else "",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }

        if (c.kind != ChKind.MASTER) {
            Slider(
                value = c.pan, onValueChange = { vm.changeChannel(c.id) { ch -> ch.copy(pan = it) } },
                valueRange = -1f..1f, modifier = Modifier.fillMaxWidth().height(28.dp)
                    .semantics { contentDescription = panDesc },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
            ToggleChip("M", c.mute, { vm.editChannel(c.id) { it.copy(mute = !it.mute) } },
                stringResource(R.string.cd_mute, c.name), Modifier.weight(1f), MaterialTheme.colorScheme.error)
            if (c.kind == ChKind.AUDIO) {
                ToggleChip("S", c.solo, { vm.editChannel(c.id) { it.copy(solo = !it.solo) } },
                    stringResource(R.string.cd_solo, c.name), Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
            }
        }
        // fader + meter
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Fader(
                db = c.faderDb, label = stringResource(R.string.cd_fader, c.name),
                onGestureStart = { vm.beginGesture() },
                onChange = { db -> vm.changeChannel(c.id) { ch -> ch.copy(faderDb = db) } },
                modifier = Modifier.fillMaxHeight().width(44.dp), color = accent,
            )
            val ch = c.id
            LevelMeter(
                peakL = { vm.meters[2 * ch] }, peakR = { vm.meters[2 * ch + 1] },
                modifier = Modifier.fillMaxHeight().clickable { vm.clearClip() },
            )
        }
        Text(
            FaderMap.format(c.faderDb) + " dB" + if (c.id in vm.clipped) " ●" else "",
            fontSize = 12.sp, color = if (c.id in vm.clipped) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Tombol Mulai/Berhenti + info latency, dipakai di bilah atas aplikasi. */
@Composable
fun EngineButton(vm: MixerViewModel, onToggle: () -> Unit) {
    Button(onClick = onToggle) {
        Text(stringResource(if (vm.running) R.string.stop else R.string.start))
    }
}

@Suppress("unused")
private val keepImport = N.MAX_CHANNELS
