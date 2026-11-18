package com.gpaudio.gpmix.model

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Pemetaan fader: posisi 0..1 <-> dB. 0 dB di posisi 0,75; +6 dB di 1,0; -60 dB di 0 (bawah = "-inf"). */
object FaderMap {
    private const val ZERO_POS = 0.75f

    fun posToDb(pos: Float): Float {
        val p = if (pos.isNaN()) 0f else pos.coerceIn(0f, 1f)
        return if (p >= ZERO_POS) (p - ZERO_POS) / (1f - ZERO_POS) * 6f
        else {
            val t = 1f - p / ZERO_POS
            -60f * t * t
        }
    }

    fun dbToPos(db: Float): Float {
        val d = Db.clampFader(db)
        return if (d >= 0f) ZERO_POS + d / 6f * (1f - ZERO_POS)
        else ZERO_POS * (1f - sqrt(-d / 60f))
    }

    /** Linear peak -> dBFS (minimum -90). */
    fun linToDb(x: Float): Float = if (x <= 1e-5f) -90f else 20f * log10(x)

    /** dBFS -> posisi meter 0..1 (rentang -60..+3). */
    fun meterPos(db: Float): Float = ((db + 60f) / 63f).coerceIn(0f, 1f)

    fun format(db: Float): String = if (db <= -59.9f) "-∞" else String.format(java.util.Locale.US, "%+.1f", db)

    @Suppress("unused")
    private fun sq(x: Float) = x.pow(2)
}
