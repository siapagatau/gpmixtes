@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gpaudio.gpmix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gpaudio.gpmix.MixerViewModel
import com.gpaudio.gpmix.R
import com.gpaudio.gpmix.model.ParamInfo
import com.gpaudio.gpmix.model.ParamScale
import com.gpaudio.gpmix.model.withInsert

/** Editor efek layar penuh. Semua kontrol dibuat otomatis dari deskriptor parameter di engine C++. */
@Composable
fun EffectEditor(
    vm: MixerViewModel,
    channelId: Int,
    slot: Int,
    onSavePreset: (String) -> Unit,
    onLoadPreset: () -> Unit,
) {
    val ch = vm.project.scene.channel(channelId)
    val ins = ch?.inserts?.getOrNull(slot)
    val info = ins?.let { vm.catalog.info(it.type) }
    if (ch == null || ins == null || info == null) return
    val close = { vm.editingInsert = null }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(info.name, style = MaterialTheme.typography.titleLarge)
                        Text(ch.name + " · " + info.category, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = close) { Text(stringResource(R.string.close)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToggleChip(
                        stringResource(R.string.bypass), ins.bypass,
                        { vm.editChannel(channelId) { c -> c.withInsert(slot) { it.copy(bypass = !it.bypass) } } },
                        stringResource(R.string.bypass),
                    )
                    OutlinedButton(onClick = { vm.editChannel(channelId) { c -> c.withInsert(slot) { it.copy(params = info.defaults()) } } }) {
                        Text(stringResource(R.string.reset))
                    }
                    if (info.params.any { it.name.contains("Threshold") } && info.category == "Dynamics") {
                        Text(stringResource(R.string.gain_reduction, vm.fxGainReduction), fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vm.savePreset()?.let(onSavePreset) }) { Text(stringResource(R.string.preset_save)) }
                    OutlinedButton(onClick = onLoadPreset) { Text(stringResource(R.string.preset_load)) }
                    OutlinedButton(onClick = { vm.moveInsert(channelId, slot, -1) }, enabled = slot > 0) { Text("↑") }
                    OutlinedButton(onClick = { vm.moveInsert(channelId, slot, 1) }, enabled = slot < ch.inserts.size - 1) { Text("↓") }
                    OutlinedButton(onClick = { vm.removeInsert(channelId, slot) }) { Text(stringResource(R.string.delete)) }
                }
                if (info.usesKey) KeySourcePicker(vm, channelId, slot, ins.keySource)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    var lastGroup: String? = null
                    info.params.forEachIndexed { i, pi ->
                        if (pi.group != lastGroup) {
                            lastGroup = pi.group
                            if (pi.group.isNotEmpty()) SectionTitle(pi.group)
                        }
                        ParamRow(vm, channelId, slot, i, pi, ins.params.getOrElse(i) { pi.def })
                    }
                }
            }
        }
    }
}

@Composable
private fun ParamRow(vm: MixerViewModel, id: Int, slot: Int, index: Int, p: ParamInfo, v: Float) {
    val text = ParamScale.format(p, v)
    when {
        p.isToggle -> Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(p.name)
            Switch(checked = v > 0.5f, onCheckedChange = { on ->
                vm.editChannel(id) { c -> c.withInsert(slot) { it.copy(params = it.params.toMutableList().also { l -> l[index] = if (on) 1f else 0f }) } }
            })
        }
        p.isChoice -> {
            var open by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(p.name)
                Box {
                    OutlinedButton(onClick = { open = true }) { Text(text) }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        p.choices.forEachIndexed { k, name ->
                            DropdownMenuItem(text = { Text(name) }, onClick = {
                                open = false
                                vm.editChannel(id) { c -> c.withInsert(slot) { it.copy(params = it.params.toMutableList().also { l -> l[index] = k.toFloat() }) } }
                            })
                        }
                    }
                }
            }
        }
        else -> Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(p.name, fontSize = 14.sp)
                Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            }
            GpSlider(
                value = ParamScale.toPos(p, v),
                onChange = { pos -> vm.setFxParam(id, slot, index, ParamScale.fromPos(p, pos)) },
                onStart = { vm.beginGesture() },
                desc = p.name, valueText = text,
            )
        }
    }
}

@Composable
private fun KeySourcePicker(vm: MixerViewModel, id: Int, slot: Int, key: Int) {
    var open by remember { mutableStateOf(false) }
    val scene = vm.project.scene
    val cur = scene.channel(key)?.name ?: stringResource(R.string.key_self)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Text(stringResource(R.string.key_source))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(cur) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.key_self)) }, onClick = {
                    open = false; vm.editChannel(id) { c -> c.withInsert(slot) { it.copy(keySource = -1) } }
                })
                scene.channels.filter { it.id != id && it.kind != com.gpaudio.gpmix.model.ChKind.MASTER }.forEach { o ->
                    DropdownMenuItem(text = { Text(o.name) }, onClick = {
                        open = false; vm.editChannel(id) { c -> c.withInsert(slot) { it.copy(keySource = o.id) } }
                    })
                }
            }
        }
    }
}
