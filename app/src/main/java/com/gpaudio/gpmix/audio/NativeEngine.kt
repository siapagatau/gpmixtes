package com.gpaudio.gpmix.audio

/**
 * Jembatan JNI ke engine C++ (mixer + Oboe). Nama dan tanda tangan harus sama persis dengan
 * jni_bridge.cpp. Semua pemanggilan aman dari thread mana pun (engine memakai atomik/mutex sendiri).
 */
object NativeEngine {
    // Konstanta ini cermin dari mixer.h (enum ChType, InKind, ChParam, PadParam).
    const val CH_AUDIO = 0
    const val CH_AUX = 1
    const val CH_GROUP = 2
    const val CH_MASTER = 3

    const val IN_NONE = 0
    const val IN_HW = 1
    const val IN_BUS = 2
    const val IN_PADS = 3
    const val IN_PLAYER = 4
    const val IN_APP = 5

    const val SRC_PADS = 1
    const val SRC_PLAYER = 2
    const val SRC_APP = 4

    const val CP_FADER_DB = 0
    const val CP_PAN = 1
    const val CP_TRIM_DB = 2
    const val CP_MUTE = 3
    const val CP_SOLO = 4
    const val CP_SAFE = 5
    const val CP_POLARITY = 6
    const val CP_STEREO = 7
    const val CP_HPF_ON = 8
    const val CP_HPF_HZ = 9
    const val CP_HPF_SLOPE = 10
    const val CP_LPF_ON = 11
    const val CP_LPF_HZ = 12
    const val CP_LPF_SLOPE = 13
    const val CP_OUT_MASK = 14
    const val CP_GROUP_INDEX = 15

    const val PP_GAIN_DB = 0
    const val PP_START = 1
    const val PP_END = 2
    const val PP_CHOKE = 3
    const val PP_NORMALIZE = 4

    const val MAX_CHANNELS = 64
    const val NUM_BUSES = 8
    const val NUM_GROUPS = 8
    const val NUM_PADS = 16
    const val MAX_HW_IN = 8
    const val MAX_INSERTS = 16
    const val MAX_SENDS = 16

    @Volatile private var loaded = false
    @Volatile var loadError: String? = null
        private set

    /** Muat library native. Mengembalikan false (dan mengisi loadError) bila gagal; tidak melempar. */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("gpmix")
            loaded = true
            true
        } catch (t: Throwable) {
            loadError = t.message ?: t.toString()
            false
        }
    }

    /** 0 = ok, 1 = output gagal, 2 = output ok tetapi input gagal. */
    external fun start(inputDeviceId: Int, outputDeviceId: Int, inputChannels: Int, bufferMultiplier: Int): Int
    external fun stop()
    /** [sampleRate, framesPerBurst, bufferFrames, inputChannels, latencyMs, xruns, running] */
    external fun info(): FloatArray
    external fun describeEffects(): String

    external fun addChannel(id: Int, type: Int): Boolean
    external fun removeChannel(id: Int): Boolean
    external fun setChannelParam(id: Int, param: Int, value: Float)
    external fun setInput(id: Int, kind: Int, index: Int, extraMask: Int)
    external fun setSend(id: Int, slot: Int, dest: Int, levelDb: Float, mute: Boolean, pre: Boolean)
    external fun setInsert(id: Int, slot: Int, type: Int): Boolean
    external fun setFxParam(id: Int, slot: Int, index: Int, value: Float)
    external fun setInsertFlags(id: Int, slot: Int, bypass: Boolean, keySource: Int)
    external fun fxGainReduction(id: Int, slot: Int): Float
    external fun setTempo(bpm: Float)
    external fun setDuckingEnabled(on: Boolean)
    external fun setPanLawDb(db: Float)
    external fun setMasterMono(on: Boolean)

    /** out[192]: 0..127 = peak L/R berpasangan per channel, 128..191 = latency (ms) per channel. */
    external fun readMeters(out: FloatArray)
    external fun padsPeak(): Float

    external fun loadPad(index: Int, interleaved: FloatArray, channels: Int, rate: Int)
    external fun clearPad(index: Int)
    external fun setPadParam(index: Int, param: Int, value: Float)
    external fun setPadsMasterDb(db: Float)
    external fun triggerPad(index: Int, velocity: Float)
    external fun stopPads()
    external fun padPlayingMask(): Int

    external fun loadPlayer(stereo: ShortArray, frames: Int, rate: Int)
    external fun clearPlayer()
    external fun playerPlay(on: Boolean)
    external fun playerLoop(on: Boolean)
    external fun playerSeek(frame: Long)
    external fun playerVolumeDb(db: Float)
    external fun playerPos(): Long
    external fun playerPlaying(): Boolean

    external fun pushAppAudio(stereo: FloatArray, frames: Int): Int

    external fun retroEnable(on: Boolean): Boolean
    external fun retroEnabled(): Boolean
    external fun retroBegin(): Int
    external fun retroRead(offsetFrames: Int, dst: ShortArray, frames: Int): Int
    external fun retroRate(): Int

    external fun recStart(sourceChannel: Int): Boolean
    external fun recStop()
    external fun recRead(dst: FloatArray, maxFrames: Int): Int
    external fun recOverruns(): Int
}
