package com.gpaudio.gpmix.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build

/**
 * Menangkap audio aplikasi lain (game, pemutar musik, panggilan yang mengizinkan) lewat AudioPlaybackCapture (Android 10+)
 * dan memasukkannya ke engine sebagai sumber "App". Audio asli tetap terdengar di perangkat (batasan Android).
 */
class AppCapture(private val projection: MediaProjection, private val sampleRate: Int) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @Volatile var lastError: String? = null
        private set
    val active: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < 29 || running) return false
        return try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBytes = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val rec = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setAudioPlaybackCaptureConfig(cfg)
                .setBufferSizeInBytes(maxOf(minBytes, 8192 * 8))
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                lastError = "AudioRecord gagal diinisialisasi"
                return false
            }
            record = rec
            running = true
            rec.startRecording()
            thread = Thread({ loop(rec) }, "gpmix-appcapture").also {
                it.priority = Thread.MAX_PRIORITY
                it.start()
            }
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.toString()
            running = false
            false
        }
    }

    private fun loop(rec: AudioRecord) {
        val buf = FloatArray(1024 * 2)
        while (running) {
            val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            if (n <= 0) {
                if (n < 0) { lastError = "read error $n"; break }
                continue
            }
            NativeEngine.pushAppAudio(buf, n / 2)
        }
        running = false
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (_: InterruptedException) { }
        try { record?.stop() } catch (_: Exception) { }
        try { record?.release() } catch (_: Exception) { }
        record = null
        thread = null
        try { projection.stop() } catch (_: Exception) { }
    }
}
