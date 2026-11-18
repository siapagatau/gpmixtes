package com.gpaudio.gpmix.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

/**
 * Dekode file audio apa pun yang didukung Android (WAV, MP3, AAC/M4A, FLAC, OGG, ...) menjadi PCM 16-bit interleaved.
 * Berjalan di thread latar. Panjang dibatasi supaya memori aman di ponsel kelas bawah.
 */
object AudioDecoder {
    class Decoded(val pcm: ShortArray, val channels: Int, val rate: Int) {
        val frames: Int get() = pcm.size / channels
    }

    class TooLongException(val maxSeconds: Int) : Exception("Audio terlalu panjang (maks $maxSeconds detik)")

    /** Kapasitas: pad pendek, Player lebih panjang. */
    const val MAX_PAD_SECONDS = 60
    const val MAX_PLAYER_BYTES = 96L shl 20  // 96 MB PCM 16-bit stereo ~ 8,7 menit @48 kHz

    fun decode(ctx: Context, uri: Uri, maxBytes: Long): Decoded {
        try {
            return decodeWithCodec(ctx, uri, maxBytes)
        } catch (e: TooLongException) {
            throw e
        } catch (e: Exception) {
            // Cadangan: WAV sederhana lewat parser sendiri.
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { s ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 48L shl 20) throw IllegalArgumentException("Format tidak didukung dan file terlalu besar")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: throw IllegalArgumentException("Tidak bisa membuka file")
            val w = try { WavLoader.parse(bytes) } catch (x: Exception) {
                throw IllegalArgumentException("Format audio tidak didukung (${e.message ?: "?"})")
            }
            if (w.data.size * 2L > maxBytes) throw TooLongException((maxBytes / (w.rate * w.channels * 2L)).toInt())
            return Decoded(ShortArray(w.data.size) { toShort(w.data[it]) }, w.channels, w.rate)
        }
    }

    private fun toShort(x: Float): Short {
        val v = Math.round(x * 32767f)
        return (if (v > 32767) 32767 else if (v < -32768) -32768 else v).toShort()
    }

    private fun decodeWithCodec(ctx: Context, uri: Uri, maxBytes: Long): Decoded {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) throw IllegalArgumentException("Tidak ada trek audio")
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            var out = ShortArray(1 shl 18)
            var n = 0
            val maxShorts = (maxBytes / 2).coerceAtMost(Int.MAX_VALUE.toLong() - 16).toInt()
            var pcmFloat = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        val size = ex.readSampleData(ib, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi >= 0 -> {
                        val ob = codec.getOutputBuffer(oi)
                        if (ob != null && info.size > 0) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val cnt = if (pcmFloat) info.size / 4 else info.size / 2
                            if (n + cnt > maxShorts) throw TooLongException((maxBytes / (rate.toLong() * channels * 2L)).toInt())
                            if (n + cnt > out.size) out = out.copyOf(maxOf(out.size * 2, n + cnt).coerceAtMost(maxShorts))
                            val bb = ob.order(ByteOrder.nativeOrder())
                            if (pcmFloat) {
                                val fb = bb.asFloatBuffer()
                                for (k in 0 until cnt) out[n + k] = toShort(fb.get())
                            } else {
                                bb.asShortBuffer().get(out, n, cnt)
                            }
                            n += cnt
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        pcmFloat = nf.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            nf.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    else -> { /* INFO_TRY_AGAIN_LATER: ulangi */ }
                }
            }
            if (channels < 1 || n < channels) throw IllegalArgumentException("Audio kosong")
            val frames = n / channels
            return Decoded(out.copyOf(frames * channels), channels, rate)
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            ex.release()
        }
    }

    /** Ubah ke stereo int16 (mono diduplikasi; >2 kanal: dua kanal pertama). */
    fun toStereo(d: Decoded): ShortArray {
        if (d.channels == 2) return d.pcm
        val f = d.frames
        val o = ShortArray(f * 2)
        for (i in 0 until f) {
            o[2 * i] = d.pcm[i * d.channels]
            o[2 * i + 1] = d.pcm[i * d.channels + (if (d.channels > 1) 1 else 0)]
        }
        return o
    }

    /** Float interleaved 1 atau 2 kanal untuk Sample Pad (kanal >2 dikurangi ke 2). */
    fun toPadFloat(d: Decoded): Pair<FloatArray, Int> {
        val ch = if (d.channels >= 2) 2 else 1
        val f = d.frames
        val o = FloatArray(f * ch)
        for (i in 0 until f) for (c in 0 until ch) o[i * ch + c] = d.pcm[i * d.channels + c] / 32768f
        return Pair(o, ch)
    }
}
