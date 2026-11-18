package com.gpaudio.gpmix.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Parser WAV minimal: PCM 16/24/32-bit dan float32. Hasil: float interleaved. */
object WavLoader {
    class Sample(val data: FloatArray, val channels: Int, val rate: Int)

    fun parse(b: ByteArray): Sample {
        require(b.size > 44 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(b, 8, 4, Charsets.US_ASCII) == "WAVE") { "Bukan file WAV" }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var fmt = 0; var ch = 0; var rate = 0; var bits = 0
        var dStart = -1; var dLen = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val len = bb.getInt(pos + 4)
            if (len < 0) break
            val body = pos + 8
            if (id == "fmt " && body + 16 <= b.size) {
                fmt = bb.getShort(body).toInt() and 0xFFFF
                ch = bb.getShort(body + 2).toInt()
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt()
            } else if (id == "data") {
                dStart = body
                dLen = minOf(len, b.size - body)
                break
            }
            pos = body + len + (len and 1)
        }
        require(dStart >= 0 && ch > 0 && rate > 0) { "Header WAV tidak lengkap" }
        require(fmt == 1 || fmt == 3 || fmt == 0xFFFE) { "Format WAV terkompresi tidak didukung" }
        val bytes = bits / 8
        require(bytes in 2..4) { "Bit depth $bits tidak didukung" }
        val frames = dLen / (bytes * ch)
        require(frames > 0) { "WAV kosong" }
        val n = frames * ch
        val out = FloatArray(n)
        when {
            fmt == 3 && bits == 32 -> for (i in 0 until n) out[i] = bb.getFloat(dStart + i * 4)
            bits == 16 -> for (i in 0 until n) out[i] = bb.getShort(dStart + i * 2) / 32768f
            bits == 24 -> for (i in 0 until n) {
                val o = dStart + i * 3
                val v = (b[o + 2].toInt() shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)
                out[i] = v / 8388608f
            }
            bits == 32 -> for (i in 0 until n) out[i] = bb.getInt(dStart + i * 4) / 2147483648f
            else -> throw IllegalArgumentException("Format WAV tidak didukung")
        }
        return Sample(out, ch, rate)
    }

    fun load(f: File): Sample {
        require(f.length() in 45..(256L shl 20)) { "Ukuran file WAV tidak wajar" }
        return parse(f.readBytes())
    }
}

/** Penulis WAV PCM 24-bit streaming (header diperbarui saat close). Aman dipakai dari satu thread. */
class Wav24Writer(file: File, private val channels: Int, private val rate: Int) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var buf = ByteArray(0)
    var closed = false
        private set

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    val frames: Long get() = dataBytes / (3L * channels)

    /** Tulis 'frames' frame float interleaved (nilai di luar -1..1 dipotong). */
    fun write(interleaved: FloatArray, frames: Int) {
        check(!closed)
        val n = frames * channels
        if (n <= 0) return
        if (buf.size < n * 3) buf = ByteArray(n * 3)
        var o = 0
        for (i in 0 until n) {
            val x = interleaved[i]
            val c = if (x.isNaN()) 0f else if (x > 1f) 1f else if (x < -1f) -1f else x
            val v = Math.round(c * 8388607f)
            buf[o++] = v.toByte()
            buf[o++] = (v shr 8).toByte()
            buf[o++] = (v shr 16).toByte()
        }
        raf.write(buf, 0, n * 3)
        dataBytes += n * 3L
    }

    fun close() {
        if (closed) return
        closed = true
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray(Charsets.US_ASCII)); h.putInt((36 + dataBytes).coerceAtMost(0xFFFFFFFFL).toInt())
        h.put("WAVE".toByteArray(Charsets.US_ASCII)); h.put("fmt ".toByteArray(Charsets.US_ASCII))
        h.putInt(16); h.putShort(1); h.putShort(channels.toShort()); h.putInt(rate)
        h.putInt(rate * channels * 3); h.putShort((channels * 3).toShort()); h.putShort(24)
        h.put("data".toByteArray(Charsets.US_ASCII)); h.putInt(dataBytes.coerceAtMost(0xFFFFFFFFL).toInt())
        raf.seek(0)
        raf.write(h.array())
        raf.close()
    }

    companion object {
        fun writeAll(file: File, data: FloatArray, channels: Int, rate: Int) {
            val w = Wav24Writer(file, channels, rate)
            try { w.write(data, data.size / channels) } finally { w.close() }
        }

        /** Tulis int16 stereo interleaved (Retrospective Record) sebagai WAV 24-bit. */
        fun writeShortsStereo(w: Wav24Writer, src: ShortArray, frames: Int) {
            val f = FloatArray(frames * 2)
            for (i in f.indices) f[i] = src[i] / 32768f
            w.write(f, frames)
        }
    }
}
