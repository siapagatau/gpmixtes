package com.gpaudio.gpmix.audio

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Perekam ke disk (WAV 24-bit stereo). Dua mode:
 *  - rekam langsung dari tap engine (Master atau satu channel), dikuras oleh thread Kotlin;
 *  - simpan Retrospective Record (hingga 5 menit terakhir).
 */
class Recorder(private val dir: File) {
    @Volatile var recording = false
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var frames = 0L
        private set
    var current: File? = null
        private set

    private var thread: Thread? = null
    private var rate = 48000

    val seconds: Float get() = frames.toFloat() / rate

    private fun newFile(prefix: String): File {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var f = File(dir, "$prefix-$stamp.wav")
        var k = 2
        while (f.exists()) f = File(dir, "$prefix-$stamp-${k++}.wav")
        return f
    }

    /** sourceChannel: -1 = Master. */
    @Synchronized
    fun start(sourceChannel: Int, sampleRate: Int): Boolean {
        if (recording) return true
        if (dir.apply { mkdirs() }.usableSpace < MIN_FREE_BYTES) { error = "Ruang penyimpanan hampir habis"; return false }
        error = null
        rate = sampleRate
        frames = 0
        val f = newFile("GPMix")
        val w = try { Wav24Writer(f, 2, sampleRate) } catch (e: Exception) { error = e.message; return false }
        current = f
        if (!NativeEngine.recStart(sourceChannel)) { w.close(); f.delete(); error = "Engine menolak merekam"; return false }
        recording = true
        thread = Thread({ drain(w, f) }, "gpmix-rec").also { it.start() }
        return true
    }

    private fun drain(w: Wav24Writer, f: File) {
        val buf = FloatArray(8192 * 2)
        try {
            while (recording) {
                val n = NativeEngine.recRead(buf, 8192)
                if (n > 0) {
                    w.write(buf, n)
                    frames += n
                    if (frames % (rate * 2L) < n && f.usableSpace < MIN_FREE_BYTES / 2) {
                        error = "Ruang penyimpanan habis, rekaman dihentikan"
                        recording = false
                    }
                } else {
                    Thread.sleep(15)
                }
            }
            // kuras sisa
            while (true) {
                val n = NativeEngine.recRead(buf, 8192)
                if (n <= 0) break
                w.write(buf, n); frames += n
            }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            recording = false
        } finally {
            w.close()
        }
    }

    /** Hentikan dan kembalikan file hasil (atau null bila tidak ada yang direkam). */
    @Synchronized
    fun stop(): File? {
        if (!recording && thread == null) return null
        recording = false
        try { thread?.join(3000) } catch (_: InterruptedException) { }
        thread = null
        NativeEngine.recStop()
        val f = current
        return if (f != null && f.exists() && frames > 0) f else { f?.delete(); null }
    }

    /** Simpan isi Retrospective Record (blocking; panggil dari thread latar). */
    fun saveRetro(): File? {
        val avail = NativeEngine.retroBegin()
        if (avail <= 0) return null
        val rt = NativeEngine.retroRate().coerceAtLeast(8000)
        if (dir.apply { mkdirs() }.usableSpace < avail.toLong() * 6 + MIN_FREE_BYTES) { error = "Ruang penyimpanan tidak cukup"; return null }
        val f = newFile("GPMix-Retro")
        val w = Wav24Writer(f, 2, rt)
        try {
            val chunk = 16384
            val src = ShortArray(chunk * 2)
            var off = 0
            while (off < avail) {
                val n = NativeEngine.retroRead(off, src, minOf(chunk, avail - off))
                if (n <= 0) break
                Wav24Writer.writeShortsStereo(w, src, n)
                off += n
            }
        } finally {
            w.close()
        }
        return f
    }

    fun list(): List<File> =
        (dir.listFiles { x -> x.isFile && x.name.endsWith(".wav") } ?: emptyArray()).sortedByDescending { it.lastModified() }

    private companion object {
        const val MIN_FREE_BYTES = 50L shl 20
    }
}
