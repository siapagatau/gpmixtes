package com.gpaudio.gpmix.engine

import com.gpaudio.gpmix.audio.NativeEngine as N
import com.gpaudio.gpmix.model.ChKind
import com.gpaudio.gpmix.model.Channel
import com.gpaudio.gpmix.model.InsertSlot
import com.gpaudio.gpmix.model.Project
import com.gpaudio.gpmix.model.Scene
import com.gpaudio.gpmix.model.SendSlot
import com.gpaudio.gpmix.model.Settings

/** Subset engine yang dipakai sinkronisasi; memungkinkan pengujian dengan engine palsu di JVM. */
interface EngineApi {
    fun addChannel(id: Int, type: Int): Boolean
    fun removeChannel(id: Int): Boolean
    fun setChannelParam(id: Int, param: Int, value: Float)
    fun setInput(id: Int, kind: Int, index: Int, extraMask: Int)
    fun setSend(id: Int, slot: Int, dest: Int, levelDb: Float, mute: Boolean, pre: Boolean)
    fun setInsert(id: Int, slot: Int, type: Int): Boolean
    fun setFxParam(id: Int, slot: Int, index: Int, value: Float)
    fun setInsertFlags(id: Int, slot: Int, bypass: Boolean, keySource: Int)
    fun setTempo(bpm: Float)
    fun setDuckingEnabled(on: Boolean)
    fun setPanLawDb(db: Float)
    fun setMasterMono(on: Boolean)
    fun setPadsMasterDb(db: Float)
}

/** Implementasi nyata: meneruskan ke engine native. */
object NativeApi : EngineApi {
    override fun addChannel(id: Int, type: Int) = N.addChannel(id, type)
    override fun removeChannel(id: Int) = N.removeChannel(id)
    override fun setChannelParam(id: Int, param: Int, value: Float) = N.setChannelParam(id, param, value)
    override fun setInput(id: Int, kind: Int, index: Int, extraMask: Int) = N.setInput(id, kind, index, extraMask)
    override fun setSend(id: Int, slot: Int, dest: Int, levelDb: Float, mute: Boolean, pre: Boolean) =
        N.setSend(id, slot, dest, levelDb, mute, pre)
    override fun setInsert(id: Int, slot: Int, type: Int) = N.setInsert(id, slot, type)
    override fun setFxParam(id: Int, slot: Int, index: Int, value: Float) = N.setFxParam(id, slot, index, value)
    override fun setInsertFlags(id: Int, slot: Int, bypass: Boolean, keySource: Int) =
        N.setInsertFlags(id, slot, bypass, keySource)
    override fun setTempo(bpm: Float) = N.setTempo(bpm)
    override fun setDuckingEnabled(on: Boolean) = N.setDuckingEnabled(on)
    override fun setPanLawDb(db: Float) = N.setPanLawDb(db)
    override fun setMasterMono(on: Boolean) = N.setMasterMono(on)
    override fun setPadsMasterDb(db: Float) = N.setPadsMasterDb(db)
}

/**
 * Menjaga engine sama dengan Project: menyimpan keadaan yang terakhir diterapkan dan hanya mengirim selisihnya.
 * Tidak thread-safe; panggil dari satu thread (thread utama).
 */
class EngineSync(private val api: EngineApi) {
    private var appliedScene: Scene? = null
    private var appliedSettings: Settings? = null
    /** Channel yang benar-benar ada di engine: id -> kind. */
    private val live = HashMap<Int, ChKind>()
    var failures = 0
        private set

    /** Lupakan keadaan (mis. setelah engine di-reset). Channel yang masih ada di engine ikut dihapus. */
    fun reset() {
        for (id in live.keys.toList()) api.removeChannel(id)
        live.clear()
        appliedScene = null
        appliedSettings = null
    }

    fun apply(p: Project) {
        applySettings(p.settings)
        applyScene(p.scene)
    }

    private fun applySettings(s: Settings) {
        val o = appliedSettings
        if (o == null || o.tempo != s.tempo) api.setTempo(s.tempo)
        if (o == null || o.duckingEnabled != s.duckingEnabled) api.setDuckingEnabled(s.duckingEnabled)
        if (o == null || o.panLawDb != s.panLawDb) api.setPanLawDb(s.panLawDb)
        if (o == null || o.masterMono != s.masterMono) api.setMasterMono(s.masterMono)
        if (o == null || o.padsMasterDb != s.padsMasterDb) api.setPadsMasterDb(s.padsMasterDb)
        appliedSettings = s
    }

    private fun applyScene(s: Scene) {
        val old = appliedScene
        if (old === s) return
        val want = s.channels.associateBy { it.id }

        // 1. hapus channel yang hilang atau berganti jenis
        for (id in live.keys.toList()) {
            val w = want[id]
            if (w == null || w.kind != live[id]) {
                api.removeChannel(id)
                live.remove(id)
            }
        }
        // 2. tambahkan channel baru (Master dulu supaya aman)
        val ordered = s.channels.sortedBy { if (it.kind == ChKind.MASTER) 0 else 1 }
        val fresh = HashSet<Int>()
        for (c in ordered) {
            if (live.containsKey(c.id)) continue
            if (api.addChannel(c.id, c.kind.native)) {
                live[c.id] = c.kind
                fresh.add(c.id)
            } else {
                failures++
            }
        }
        // 3. samakan isi
        for (c in s.channels) {
            if (!live.containsKey(c.id)) continue
            val prev = if (c.id in fresh) null else old?.channel(c.id)
            syncChannel(prev, c)
        }
        appliedScene = s
    }

    private fun syncChannel(o: Channel?, c: Channel) {
        if (o == c) return
        val id = c.id
        fun f(b: Boolean) = if (b) 1f else 0f
        fun <T> changed(a: T?, b: T) = o == null || a != b
        if (changed(o?.faderDb, c.faderDb)) api.setChannelParam(id, N.CP_FADER_DB, c.faderDb)
        if (changed(o?.pan, c.pan)) api.setChannelParam(id, N.CP_PAN, c.pan)
        if (changed(o?.trimDb, c.trimDb)) api.setChannelParam(id, N.CP_TRIM_DB, c.trimDb)
        if (changed(o?.mute, c.mute)) api.setChannelParam(id, N.CP_MUTE, f(c.mute))
        if (changed(o?.solo, c.solo)) api.setChannelParam(id, N.CP_SOLO, f(c.solo))
        if (changed(o?.safe, c.safe)) api.setChannelParam(id, N.CP_SAFE, f(c.safe))
        if (changed(o?.polarity, c.polarity)) api.setChannelParam(id, N.CP_POLARITY, f(c.polarity))
        if (changed(o?.stereo, c.stereo)) api.setChannelParam(id, N.CP_STEREO, f(c.stereo))
        if (changed(o?.hpfOn, c.hpfOn)) api.setChannelParam(id, N.CP_HPF_ON, f(c.hpfOn))
        if (changed(o?.hpfHz, c.hpfHz)) api.setChannelParam(id, N.CP_HPF_HZ, c.hpfHz)
        if (changed(o?.hpfSlope, c.hpfSlope)) api.setChannelParam(id, N.CP_HPF_SLOPE, c.hpfSlope.toFloat())
        if (changed(o?.lpfOn, c.lpfOn)) api.setChannelParam(id, N.CP_LPF_ON, f(c.lpfOn))
        if (changed(o?.lpfHz, c.lpfHz)) api.setChannelParam(id, N.CP_LPF_HZ, c.lpfHz)
        if (changed(o?.lpfSlope, c.lpfSlope)) api.setChannelParam(id, N.CP_LPF_SLOPE, c.lpfSlope.toFloat())
        if (changed(o?.outMask, c.outMask)) api.setChannelParam(id, N.CP_OUT_MASK, c.outMask.toFloat())
        if (changed(o?.groupIndex, c.groupIndex)) api.setChannelParam(id, N.CP_GROUP_INDEX, c.groupIndex.toFloat())
        if (o == null || o.inKind != c.inKind || o.inIndex != c.inIndex || o.extraMask != c.extraMask)
            api.setInput(id, c.inKind, c.inIndex, c.extraMask)
        syncSends(id, o?.sends ?: emptyList(), c.sends, fresh = o == null)
        syncInserts(id, o?.inserts ?: emptyList(), c.inserts, fresh = o == null)
    }

    private fun syncSends(id: Int, o: List<SendSlot>, n: List<SendSlot>, fresh: Boolean) {
        for (i in n.indices) {
            val s = n[i]
            if (fresh || o.getOrNull(i) != s) api.setSend(id, i, s.dest, s.levelDb, s.mute, s.pre)
        }
        for (i in n.size until o.size) api.setSend(id, i, -1, -60f, false, false)
    }

    private fun syncInserts(id: Int, o: List<InsertSlot>, n: List<InsertSlot>, fresh: Boolean) {
        for (i in n.indices) {
            val s = n[i]
            val p = o.getOrNull(i)
            if (p == null || p.type != s.type) {
                if (!api.setInsert(id, i, s.type)) { failures++; continue }
                for (k in s.params.indices) api.setFxParam(id, i, k, s.params[k])
                api.setInsertFlags(id, i, s.bypass, s.keySource)
            } else {
                for (k in s.params.indices) if (p.params.getOrNull(k) != s.params[k]) api.setFxParam(id, i, k, s.params[k])
                if (p.bypass != s.bypass || p.keySource != s.keySource) api.setInsertFlags(id, i, s.bypass, s.keySource)
            }
        }
        for (i in n.size until o.size) api.setInsert(id, i, -1)
    }
}
