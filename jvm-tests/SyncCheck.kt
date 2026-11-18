import com.gpaudio.gpmix.engine.*
import com.gpaudio.gpmix.model.*
import com.gpaudio.gpmix.audio.NativeEngine as N
import java.io.File

/** Engine palsu: mensimulasikan aturan engine (id unik, satu master, slot insert) dan mencatat panggilan. */
class FakeEngine : EngineApi {
    val kinds = HashMap<Int, Int>()
    val calls = ArrayList<String>()
    val inserts = HashMap<Pair<Int, Int>, Int>()
    val fader = HashMap<Int, Float>()
    override fun addChannel(id: Int, type: Int): Boolean {
        if (kinds.containsKey(id)) return false
        if (type == 3 && kinds.values.contains(3)) return false
        kinds[id] = type; calls.add("add $id"); return true
    }
    override fun removeChannel(id: Int): Boolean { calls.add("rm $id"); inserts.keys.removeAll { it.first == id }; return kinds.remove(id) != null }
    override fun setChannelParam(id: Int, param: Int, value: Float) { calls.add("p $id $param"); if (param == 0) fader[id] = value }
    override fun setInput(id: Int, kind: Int, index: Int, extraMask: Int) { calls.add("in $id") }
    override fun setSend(id: Int, slot: Int, dest: Int, levelDb: Float, mute: Boolean, pre: Boolean) { calls.add("send $id $slot $dest") }
    override fun setInsert(id: Int, slot: Int, type: Int): Boolean { calls.add("ins $id $slot $type"); if (type < 0) inserts.remove(id to slot) else inserts[id to slot] = type; return true }
    override fun setFxParam(id: Int, slot: Int, index: Int, value: Float) { calls.add("fx $id $slot $index") }
    override fun setInsertFlags(id: Int, slot: Int, bypass: Boolean, keySource: Int) { calls.add("flags $id $slot") }
    override fun setTempo(bpm: Float) { calls.add("tempo") }
    override fun setDuckingEnabled(on: Boolean) { calls.add("duck") }
    override fun setPanLawDb(db: Float) { calls.add("panlaw") }
    override fun setMasterMono(on: Boolean) { calls.add("mono") }
    override fun setPadsMasterDb(db: Float) { calls.add("padsdb") }
}

fun syncMain(args: Array<String>) {
    val cat = EffectCatalog.parse(File(args[0]).readText())
    val p0 = Presets.defaultProject(cat, EditionLimits.PRO)
    val eng = FakeEngine(); val sync = EngineSync(eng)
    sync.apply(p0)
    val s0 = p0.scene
    check(eng.kinds.keys == s0.channels.map { it.id }.toSet(), "semua channel scene 0 ada di engine")
    check(eng.kinds.values.count { it == 3 } == 1, "satu master")
    check(eng.inserts.size == s0.channels.sumOf { it.inserts.size }, "jumlah insert sama")
    check(eng.calls.first { it.startsWith("add") } == "add 0", "Master ditambahkan paling awal")

    // Tanpa perubahan: tidak ada panggilan
    eng.calls.clear(); sync.apply(p0)
    check(eng.calls.isEmpty(), "apply ulang tanpa perubahan = nol panggilan")

    // Ubah satu fader: tepat satu panggilan
    val mic = s0.channels.first { it.name == "Mic" }
    val p1 = p0.withScene(s0.update(mic.id) { it.copy(faderDb = -7f) })
    eng.calls.clear(); sync.apply(p1)
    check(eng.calls == listOf("p ${mic.id} 0"), "satu perubahan fader = satu panggilan, dapat ${eng.calls}")
    check(eng.fader[mic.id] == -7f, "nilai fader sampai")

    // Ubah satu parameter efek
    val ins = mic.inserts[2]
    val newIns = ins.copy(params = ins.params.toMutableList().also { it[0] = -30f })
    val p2 = p1.withScene(p1.scene.update(mic.id) { it.copy(inserts = it.inserts.toMutableList().also { l -> l[2] = newIns }) })
    eng.calls.clear(); sync.apply(p2)
    check(eng.calls == listOf("fx ${mic.id} 2 0"), "satu parameter efek = satu panggilan, dapat ${eng.calls}")

    // Ganti tipe efek di slot
    val p3 = p2.withScene(p2.scene.update(mic.id) { it.copy(inserts = it.inserts.toMutableList().also { l -> l[0] = InsertSlot(5, cat.info(5)!!.defaults()) }) })
    eng.calls.clear(); sync.apply(p3)
    check(eng.calls.first() == "ins ${mic.id} 0 5" && eng.inserts[mic.id to 0] == 5, "ganti tipe efek")

    // Pindah scene: Public Chat (id 1..4 dipakai ulang dengan jenis berbeda)
    val p4 = p3.copy(activeScene = 1)
    sync.apply(p4)
    val s1 = p4.scene
    check(eng.kinds.keys == s1.channels.map { it.id }.toSet(), "scene 1: himpunan channel sama")
    check(s1.channels.all { eng.kinds[it.id] == it.kind.native }, "scene 1: jenis channel sama")
    check(eng.inserts.size == s1.channels.sumOf { it.inserts.size }, "scene 1: insert sama (tidak ada sisa)")
    check(sync.failures == 0, "tanpa kegagalan")

    // Kembali ke scene 0 lalu ke 2
    sync.apply(p4.copy(activeScene = 0)); sync.apply(p4.copy(activeScene = 2))
    check(eng.kinds.size == p4.scenes[2].channels.size, "scene 2 bersih")

    // Hapus channel di tengah lalu reset
    sync.reset()
    check(eng.kinds.isEmpty(), "reset mengosongkan engine")
    sync.apply(p0)
    check(eng.kinds.size == s0.channels.size, "apply setelah reset")

    // Hapus insert: setInsert(-1)
    val p5 = p0.withScene(p0.scene.update(mic.id) { it.copy(inserts = it.inserts.take(1)) })
    eng.calls.clear(); sync.apply(p5)
    check(eng.calls.filter { it.startsWith("ins") }.size == mic.inserts.size - 1, "insert yang dihapus dikosongkan")
}
