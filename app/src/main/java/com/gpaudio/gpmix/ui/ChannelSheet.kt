@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gpaudio.gpmix.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpaudio.gpmix.MixerViewModel
import com.gpaudio.gpmix.R
import com.gpaudio.gpmix.audio.NativeEngine as N
import com.gpaudio.gpmix.model.ChKind
import com.gpaudio.gpmix.model.Channel
import com.gpaudio.gpmix.model.FaderMap
import com.gpaudio.gpmix.model.Ops
import com.gpaudio.gpmix.model.Out
import com.gpaudio.gpmix.model.Presets

@Composable
fun ChannelSheet(vm: MixerViewModel, id: Int) {
    val c = vm.project.scene.channel(id)
    if (c == null) return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { vm.selectedChannel = null }, sheetState = state) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            NameRow(vm, c)
            if (c.kind != ChKind.MASTER) {
                if (c.kind == ChKind.AUDIO) {
                    SectionTitle(stringResource(R.string.input_source))
                    InputPicker(vm, c)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleChip(stringResource(R.string.stereo), c.stereo, { vm.editChannel(c.id) { it.copy(stereo = !it.stereo) } }, stringResource(R.string.stereo))
                        ToggleChip(stringResource(R.string.polarity), c.polarity, { vm.editChannel(c.id) { it.copy(polarity = !it.polarity) } }, stringResource(R.string.polarity))
                        ToggleChip("SAFE", c.safe, { vm.editChannel(c.id) { it.copy(safe = !it.safe) } }, stringResource(R.string.safe_desc))
                    }
                }
                SectionTitle(stringResource(R.string.trim))
                GpSlider(c.trimDb, { v -> vm.changeChannel(c.id) { it.copy(trimDb = v) } }, { vm.beginGesture() },
                    stringResource(R.string.trim), FaderMap.format(c.trimDb) + " dB", range = -24f..24f)
                Text(FaderMap.format(c.trimDb) + " dB", fontSize = 12.sp)
                FilterSection(vm, c)
                SectionTitle(stringResource(R.string.routing))
                RoutingSection(vm, c)
                SendsSection(vm, c)
            }
            InsertsSection(vm, c)
            if (c.kind == ChKind.AUDIO) PresetSection(vm, c)
            if (c.kind != ChKind.MASTER) {
                var confirm by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { confirm = true }, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.channel_delete)) }
                if (confirm) AlertDialog(
                    onDismissRequest = { confirm = false },
                    title = { Text(stringResource(R.string.channel_delete)) },
                    text = { Text(stringResource(R.string.channel_delete_confirm, c.name)) },
                    confirmButton = { TextButton(onClick = { confirm = false; vm.removeChannel(c.id) }) { Text(stringResource(R.string.delete)) } },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
                )
            }
        }
    }
}

@Composable
private fun NameRow(vm: MixerViewModel, c: Channel) {
    var name by remember(c.id) { mutableStateOf(c.name) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            name, { name = it.take(40) }, singleLine = true, label = { Text(stringResource(R.string.name)) }, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { vm.editChannel(c.id) { it.copy(name = name.trim().ifEmpty { it.name }) } }) { Text(stringResource(R.string.ok)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
        for (i in 0 until 8) {
            ToggleChip((i + 1).toString(), c.color == i, { vm.editChannel(c.id) { it.copy(color = i) } },
                stringResource(R.string.cd_color, i + 1), Modifier.weight(1f), ChannelColors[i])
        }
    }
}

@Composable
private fun InputPicker(vm: MixerViewModel, c: Channel) {
    var open by remember { mutableStateOf(false) }
    val hwName = stringResource(R.string.src_hw, c.inIndex + 1)
    val label = when (c.inKind) {
        N.IN_HW -> hwName
        N.IN_PADS -> stringResource(R.string.src_pads)
        N.IN_PLAYER -> stringResource(R.string.src_player)
        N.IN_APP -> stringResource(R.string.src_app)
        else -> stringResource(R.string.src_none)
    }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val inCh = (if (vm.info.inputChannels > 0) vm.info.inputChannels else vm.project.settings.inputChannels).coerceIn(1, N.MAX_HW_IN)
            for (k in 0 until N.MAX_HW_IN) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.src_hw, k + 1) + if (k >= inCh) " ·" else "") },
                    onClick = { open = false; vm.editChannel(c.id) { it.copy(inKind = N.IN_HW, inIndex = k) } },
                )
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.src_pads)) }, onClick = { open = false; vm.editChannel(c.id) { it.copy(inKind = N.IN_PADS) } })
            DropdownMenuItem(text = { Text(stringResource(R.string.src_player)) }, onClick = { open = false; vm.editChannel(c.id) { it.copy(inKind = N.IN_PLAYER) } })
            DropdownMenuItem(text = { Text(stringResource(R.string.src_app)) }, onClick = { open = false; vm.editChannel(c.id) { it.copy(inKind = N.IN_APP) } })
            DropdownMenuItem(text = { Text(stringResource(R.string.src_none)) }, onClick = { open = false; vm.editChannel(c.id) { it.copy(inKind = N.IN_NONE) } })
        }
    }
}

@Composable
private fun FilterSection(vm: MixerViewModel, c: Channel) {
    SectionTitle(stringResource(R.string.filters))
    val slopes = listOf("12", "24", "36", "48")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.hpf) + " " + c.hpfHz.toInt() + " Hz · " + slopes[c.hpfSlope.coerceIn(0, 3)] + " dB/oct")
        Switch(c.hpfOn, { vm.editChannel(c.id) { ch -> ch.copy(hpfOn = it) } })
    }
    GpSlider(c.hpfHz, { v -> vm.changeChannel(c.id) { it.copy(hpfHz = v) } }, { vm.beginGesture() }, stringResource(R.string.hpf), "${c.hpfHz.toInt()} Hz", range = 20f..1000f)
    SlopePicker(c.hpfSlope) { s -> vm.editChannel(c.id) { it.copy(hpfSlope = s) } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.lpf) + " " + c.lpfHz.toInt() + " Hz · " + slopes[c.lpfSlope.coerceIn(0, 3)] + " dB/oct")
        Switch(c.lpfOn, { vm.editChannel(c.id) { ch -> ch.copy(lpfOn = it) } })
    }
    GpSlider(c.lpfHz, { v -> vm.changeChannel(c.id) { it.copy(lpfHz = v) } }, { vm.beginGesture() }, stringResource(R.string.lpf), "${c.lpfHz.toInt()} Hz", range = 1000f..20000f)
    SlopePicker(c.lpfSlope) { s -> vm.editChannel(c.id) { it.copy(lpfSlope = s) } }
}

@Composable
private fun SlopePicker(cur: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("12", "24", "36", "48").forEachIndexed { i, t ->
            ToggleChip(t, cur == i, { onPick(i) }, "$t dB/oct", Modifier.weight(1f))
        }
    }
}

@Composable
private fun RoutingSection(vm: MixerViewModel, c: Channel) {
    val scene = vm.project.scene
    Text(stringResource(R.string.routing_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState())) {
        ToggleChip(stringResource(R.string.kind_master), c.outMask and Out.MASTER != 0,
            { vm.editChannel(c.id) { Ops.toggleOut(it, Out.MASTER) } }, stringResource(R.string.route_to, stringResource(R.string.kind_master)))
        for (g in scene.channels.filter { it.kind == ChKind.GROUP && it.id != c.id }) {
            val bit = Out.group(g.groupIndex)
            ToggleChip(g.name, c.outMask and bit != 0, { vm.editChannel(c.id) { Ops.toggleOut(it, bit) } }, stringResource(R.string.route_to, g.name))
        }
        if (c.kind != ChKind.AUX) {
            for (a in scene.channels.filter { it.kind == ChKind.AUX }) {
                val bit = Out.bus(a.inIndex)
                ToggleChip(a.name, c.outMask and bit != 0, { vm.editChannel(c.id) { Ops.toggleOut(it, bit) } }, stringResource(R.string.route_to, a.name))
            }
        }
    }
}

@Composable
private fun SendsSection(vm: MixerViewModel, c: Channel) {
    val scene = vm.project.scene
    val auxes = scene.channels.filter { it.kind == ChKind.AUX && !Ops.busLoop(scene, c, it.inIndex) }
    SectionTitle(stringResource(R.string.sends))
    c.sends.forEachIndexed { i, s ->
        val aux = scene.channels.firstOrNull { it.kind == ChKind.AUX && it.inIndex == s.dest }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text((aux?.name ?: "Bus ${s.dest + 1}"), Modifier.weight(0.8f), fontSize = 13.sp)
            ToggleChip("PRE", s.pre, { vm.editChannel(c.id) { ch -> ch.copy(sends = ch.sends.toMutableList().also { l -> l[i] = s.copy(pre = !s.pre) }) } }, stringResource(R.string.send_pre))
            ToggleChip("M", s.mute, { vm.editChannel(c.id) { ch -> ch.copy(sends = ch.sends.toMutableList().also { l -> l[i] = s.copy(mute = !s.mute) }) } }, stringResource(R.string.cd_mute, aux?.name ?: ""))
            TextButton(onClick = { vm.editChannel(c.id) { ch -> ch.copy(sends = ch.sends.filterIndexed { k, _ -> k != i }) } }) { Text("✕") }
        }
        GpSlider(s.levelDb, { v -> vm.changeChannel(c.id) { ch -> ch.copy(sends = ch.sends.toMutableList().also { l -> l[i] = s.copy(levelDb = v) }) } },
            { vm.beginGesture() }, stringResource(R.string.send_level, aux?.name ?: ""), FaderMap.format(s.levelDb) + " dB", range = -60f..12f)
    }
    var open by remember { mutableStateOf(false) }
    val free = auxes.filter { a -> c.sends.none { it.dest == a.inIndex } }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = free.isNotEmpty()) { Text(stringResource(R.string.send_add)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            free.forEach { a ->
                DropdownMenuItem(text = { Text(a.name) }, onClick = {
                    open = false
                    val upd = Ops.addSend(c, scene, a.inIndex, vm.limits)
                    if (upd == null) vm.say(R.string.msg_limit_reached, vm.limits.name) else vm.editChannel(c.id) { upd }
                })
            }
        }
    }
}

@Composable
private fun InsertsSection(vm: MixerViewModel, c: Channel) {
    SectionTitle(stringResource(R.string.inserts, c.inserts.size, vm.limits.inserts))
    c.inserts.forEachIndexed { i, ins ->
        val name = vm.catalog.info(ins.type)?.name ?: "?"
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.editingInsert = c.id to i }, modifier = Modifier.weight(1f)) {
                Text((i + 1).toString() + ". " + name + if (ins.bypass) " (bypass)" else "")
            }
        }
    }
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = c.inserts.size < vm.limits.inserts) { Text(stringResource(R.string.insert_add)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (cat in vm.catalog.categories) {
                for (e in vm.catalog.effects.filter { it.category == cat }) {
                    DropdownMenuItem(text = { Text(e.name + " · " + cat) }, onClick = { open = false; vm.addInsert(c.id, e.type) })
                }
            }
        }
    }
}

@Composable
private fun PresetSection(vm: MixerViewModel, c: Channel) {
    SectionTitle(stringResource(R.string.channel_presets))
    Text(stringResource(R.string.channel_presets_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Presets.channelPresetNames.forEach { n ->
            OutlinedButton(onClick = { vm.applyChannelPreset(c.id, n) }) { Text(n) }
        }
    }
}
