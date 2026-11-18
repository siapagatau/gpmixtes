package com.gpaudio.gpmix.model

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** Skala slider untuk parameter efek: logaritmik untuk Hz/waktu berentang lebar, linear selain itu. */
object ParamScale {
    fun isLog(p: ParamInfo): Boolean =
        p.kind == ParamInfo.KIND_FLOAT && p.min > 0f && (p.unit == "Hz" || p.max / p.min >= 50f)

    fun toPos(p: ParamInfo, v: Float): Float {
        val x = p.clamp(v)
        if (p.max <= p.min) return 0f
        return if (isLog(p)) (ln(x / p.min) / ln(p.max / p.min)).coerceIn(0f, 1f)
        else ((x - p.min) / (p.max - p.min)).coerceIn(0f, 1f)
    }

    fun fromPos(p: ParamInfo, pos: Float): Float {
        val t = if (pos.isNaN()) 0f else pos.coerceIn(0f, 1f)
        val v = if (isLog(p)) p.min * exp(t * ln(p.max / p.min)) else p.min + t * (p.max - p.min)
        return p.clamp(v)
    }

    fun format(p: ParamInfo, v: Float): String {
        if (p.isToggle) return if (v > 0.5f) "on" else "off"
        if (p.isChoice) return p.choices.getOrNull(v.toInt()) ?: "?"
        val a = abs(v)
        val loc = java.util.Locale.US
        if (p.unit == "Hz" && a >= 1000f) return String.format(loc, "%.2f kHz", v / 1000f)
        val s = when {
            a >= 100f -> String.format(loc, "%.0f", v)
            a >= 10f -> String.format(loc, "%.1f", v)
            else -> String.format(loc, "%.2f", v)
        }
        return if (p.unit.isEmpty()) s else "$s ${p.unit}"
    }
}
