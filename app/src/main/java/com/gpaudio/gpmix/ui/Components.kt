package com.gpaudio.gpmix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpaudio.gpmix.model.FaderMap

/**
 * Fader vertikal. Seret untuk mengubah, ketuk dua kali untuk kembali ke 0 dB.
 * TalkBack: dapat disesuaikan (naik/turun) lewat aksi 'setProgress'.
 */
@Composable
fun Fader(
    db: Float,
    label: String,
    onGestureStart: () -> Unit,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val cb by rememberUpdatedState(onChange)
    val start by rememberUpdatedState(onGestureStart)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tick = MaterialTheme.colorScheme.outline
    val pos = FaderMap.dbToPos(db)
    val desc = FaderMap.format(db) + " dB"
    Box(
        modifier
            .width(44.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = desc
                progressBarRangeInfo = ProgressBarRangeInfo(pos, 0f..1f)
                setProgress { v -> start(); cb(FaderMap.posToDb(v)); true }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { start() },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val p = 1f - (change.position.y / h).coerceIn(0f, 1f)
                        cb(FaderMap.posToDb(p))
                    },
                )
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { start(); cb(0f) }) }
    ) {
        Canvas(Modifier.fillMaxHeight().width(44.dp)) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            drawRoundRect(track, Offset(cx - 3.dp.toPx(), 0f), Size(6.dp.toPx(), h), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            // tanda 0 dB, -10, -20, -30, -40, -50
            for (d in listOf(6, 0, -10, -20, -30, -40, -50)) {
                val y = h * (1f - FaderMap.dbToPos(d.toFloat()))
                drawLine(tick, Offset(cx - 14.dp.toPx(), y), Offset(cx - 8.dp.toPx(), y), if (d == 0) 2.dp.toPx() else 1.dp.toPx())
            }
            val y = h * (1f - pos)
            drawRoundRect(color, Offset(cx - 16.dp.toPx(), y - 9.dp.toPx()), Size(32.dp.toPx(), 18.dp.toPx()),
                androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
            drawLine(Color.Black, Offset(cx - 12.dp.toPx(), y), Offset(cx + 12.dp.toPx(), y), 2.dp.toPx())
        }
    }
}

/** Meter peak stereo. 'peak' dipanggil di fase gambar sehingga hanya meter yang digambar ulang (bukan seluruh layar). */
@Composable
fun LevelMeter(peakL: () -> Float, peakR: () -> Float, modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.width(14.dp)) {
        val w = size.width
        val h = size.height
        val bw = (w - 2.dp.toPx()) / 2f
        fun bar(x: Float, lin: Float) {
            drawRect(bg, Offset(x, 0f), Size(bw, h))
            val p = FaderMap.meterPos(FaderMap.linToDb(lin))
            val top = h * (1f - p)
            val yellowAt = h * (1f - FaderMap.meterPos(-12f))
            val redAt = h * (1f - FaderMap.meterPos(-3f))
            // gambar per zona
            fun zone(from: Float, to: Float, c: Color) {
                val a = maxOf(from, top); val b = to
                if (b > a) drawRect(c, Offset(x, a), Size(bw, b - a))
            }
            zone(yellowAt, h, MeterGreen)
            zone(redAt, yellowAt, MeterYellow)
            zone(0f, redAt, MeterRed)
        }
        bar(0f, peakL())
        bar(bw + 2.dp.toPx(), peakR())
    }
}

/** Tombol kecil bertoggle (M / S / SAFE / dll). Memuat deskripsi aksesibilitas dan status. */
@Composable
fun ToggleChip(
    text: String,
    on: Boolean,
    onClick: () -> Unit,
    desc: String,
    modifier: Modifier = Modifier,
    onColor: Color = MaterialTheme.colorScheme.primary,
) {
    val bg = if (on) onColor else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick, role = Role.Switch)
            .semantics { contentDescription = desc; selected = on; stateDescription = if (on) "on" else "off" }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.padding(top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun Divider1(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.surfaceVariant))
}

@Suppress("unused")
val StripWidth: Dp = 92.dp

/** Slider yang membuka satu langkah undo per gestur dan memberi deskripsi aksesibilitas. */
@Composable
fun GpSlider(
    value: Float,
    onChange: (Float) -> Unit,
    onStart: () -> Unit,
    desc: String,
    valueText: String,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.material3.Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = { if (!open) { open = true; onStart() }; onChange(it) },
        onValueChangeFinished = { open = false },
        valueRange = range,
        modifier = modifier.semantics { contentDescription = desc; stateDescription = valueText },
    )
}
