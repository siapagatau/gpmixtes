import com.gpaudio.gpmix.model.*
import com.gpaudio.gpmix.audio.NativeEngine as N
import java.io.File

var checks = 0
var fails = 0
fun check(c: Boolean, msg: String) { checks++; if (!c) { fails++; println("GAGAL: $msg") } }
fun <T> expectThrows(msg: String, f: () -> T) {
    checks++
    try { f(); fails++; println("GAGAL (tidak melempar): $msg") } catch (e: IllegalArgumentException) { }
}

fun main(args: Array<String>) {
    val cat = EffectCatalog.parse(File(args[0]).readText())
    check(cat.effects.size == 18, "18 efek, dapat ${cat.effects.size}")
    check(cat.effects.mapIndexed { i, e -> e.type == i }.all { it }, "tipe efek berurutan")
    check(cat.info(3)?.name == "Compressor", "tipe 3 = Compressor")
    check(cat.info(99) == null && cat.info(-1) == null, "tipe di luar jangkauan = null")
    val comp = cat.info(3)!!
    check(comp.usesKey, "Compressor memakai key")
    check(comp.params.size == 9 && comp.params[0].name == "Threshold" && comp.params[0].unit == "dB", "param Compressor")
    check(cat.info(9)!!.params[0].isChoice && cat.info(9)!!.params[0].choices == listOf("Room", "Hall", "Plate"), "pilihan Reverb")
    check(cat.info(8)!!.params.first { it.name == "Note" }.choices.size == 16, "16 pilihan note Delay")
    check(cat.info(0)!!.params.size == 37, "EQ 37 param, dapat ${cat.info(0)!!.params.size}")
    check(comp.params[0].clamp(10f) == 0f && comp.params[0].clamp(Float.NaN) == comp.params[0].def, "clamp param")

    // Edisi
    check(EditionLimits.byName("pro").audioInputs == 256 && EditionLimits.byName("x") == EditionLimits.LITE, "edisi")

    // Proyek default untuk tiap edisi
    for (lim in listOf(EditionLimits.LITE, EditionLimits.PLUS, EditionLimits.PRO)) {
        val p = Presets.defaultProject(cat, lim)
        check(p.name == "My Preset" && p.scenes.map { it.name } == listOf("Singing", "Public Chat", "Private Chat"), "scene default ${lim.name}")
        for (s in p.scenes) {
            check(s.count(ChKind.MASTER) == 1, "satu master di ${s.name}")
            check(s.channels.map { it.id }.distinct().size == s.channels.size, "id unik di ${s.name}")
            check(s.channels.all { it.inserts.size <= lim.inserts && it.sends.size <= lim.sends }, "batas insert/send ${lim.name}")
            for (c in s.channels) for (i in c.inserts) {
                val info = cat.info(i.type)!!
                check(i.params.size == info.params.size, "jumlah param ${info.name}")
                check(i.params.indices.all { k -> i.params[k] in info.params[k].min..info.params[k].max }, "param dalam rentang ${info.name}")
                if (i.keySource >= 0) check(s.channel(i.keySource) != null, "key mengarah ke channel ada")
            }
        }
    }
    val lite = Presets.defaultProject(cat, EditionLimits.LITE)
    val sing = lite.scenes[0]
    check(sing.channels.all { it.inserts.size <= 4 }, "Lite: insert <= 4")
    check(sing.channels.first { it.name == "Mic" }.sends.size == 3, "Mic punya 3 send")
    check(sing.channel(2)!!.outMask == Out.group(0), "Mic -> Group")
    check(sing.channels.filter { it.kind == ChKind.AUX }.map { it.inIndex }.sorted() == listOf(0, 1, 2), "Aux di bus 0..2")

    // Round trip JSON
    val full = Presets.defaultProject(cat, EditionLimits.PRO)
    val js = ProjectJson.toJson(full)
    val back = ProjectJson.fromJson(js, cat)
    check(back == full, "round trip JSON identik")
    check(ProjectJson.toJson(back) == js, "JSON stabil")

    // Pembacaan toleran / aman
    expectThrows("bukan JSON") { ProjectJson.fromJson("halo", cat) }
    expectThrows("format salah") { ProjectJson.fromJson("""{"format":"x","version":1}""", cat) }
    expectThrows("versi masa depan") { ProjectJson.fromJson("""{"format":"gpmixa","version":99,"scenes":[]}""", cat) }
    expectThrows("tanpa scene") { ProjectJson.fromJson("""{"format":"gpmixa","version":1,"scenes":[]}""", cat) }
    val evil = """{"format":"gpmixa","version":1,"name":"x","activeScene":9,"settings":{"tempo":99999,"bufMult":1},
      "pads":[{"file":"../../etc/passwd","gain":1000}],
      "scenes":[{"name":"s","nextId":500,"channels":[{"id":99,"kind":0,"fader":1e9,"pan":-7,"inserts":[{"type":3,"params":[1e30,"x"]},{"type":77}],"sends":[{"dest":50,"db":-1e9}],"out":-5}]}]}"""
    val e = ProjectJson.fromJson(evil, cat)
    check(e.activeScene == 0 && e.settings.tempo == 300f && e.settings.bufferMultiplier == 2, "pengaman settings")
    check(e.pads.size == 16 && e.pads[0].file == null && e.pads[0].gainDb == 24f, "pengaman pad (path traversal)")
    val sc = e.scenes[0]
    check(sc.count(ChKind.MASTER) == 1 && sc.channels[0].kind == ChKind.MASTER, "master dibuat otomatis")
    val ch = sc.channels.first { it.kind == ChKind.AUDIO }
    check(ch.id == 63 && ch.faderDb == 6f && ch.pan == -1f, "clamp channel")
    check(ch.inserts.size == 1 && ch.inserts[0].params[0] == 0f && ch.inserts[0].params[1] == comp.params[1].def, "param rusak -> clamp/default, efek tak dikenal dibuang")
    check(ch.sends.single().dest == 7 && ch.sends.single().levelDb == -60f && ch.outMask == 0, "send/out di-clamp")

    // Preset efek
    val pr = ProjectJson.FxPreset(3, "Tes", comp.defaults())
    check(ProjectJson.presetFromJson(ProjectJson.presetToJson(pr), cat) == pr, "round trip preset efek")
    expectThrows("preset salah tipe") { ProjectJson.presetFromJson("""{"format":"gpmixfx","type":500}""", cat) }

    // Operasi
    val limLite = EditionLimits.LITE
    var s = Scene("t", listOf(Channel(0, ChKind.MASTER, "Master", outMask = 0)), 1)
    for (i in 1..8) s = Ops.addChannel(s, ChKind.AUDIO, limLite, "A$i") ?: error("tambah audio $i")
    check(Ops.addChannel(s, ChKind.AUDIO, limLite, "A9") == null, "Lite: audio maks 8")
    check(s.channels.filter { it.kind == ChKind.AUDIO }.map { it.inIndex }.max() <= N.MAX_HW_IN - 1, "indeks HW <= 7")
    s = Ops.addChannel(s, ChKind.AUX, limLite, "X1")!!
    s = Ops.addChannel(s, ChKind.GROUP, limLite, "G1")!!
    s = Ops.addChannel(s, ChKind.GROUP, limLite, "G2")!!
    check(Ops.addChannel(s, ChKind.GROUP, limLite, "G3") == null, "Lite: group maks 2")
    val aux = s.channels.first { it.kind == ChKind.AUX }
    val g1 = s.channels.first { it.kind == ChKind.GROUP && it.groupIndex == 0 }
    var a1 = s.channels.first { it.name == "A1" }
    a1 = Ops.addSend(a1, s, aux.inIndex, limLite)!!
    a1 = a1.copy(outMask = Out.group(0), inserts = listOf(InsertSlot(3, comp.defaults(), keySource = -1)))
    s = s.update(a1.id) { a1 }
    val a2 = s.channel(a1.id + 1)!!.let { it.copy(inserts = listOf(InsertSlot(6, cat.info(6)!!.defaults(), keySource = a1.id))) }
    s = s.update(a2.id) { a2 }
    check(Ops.addSend(a1, s, aux.inIndex, limLite) == null, "send ganda ke bus yang sama ditolak")
    check(Ops.addSend(aux, s, aux.inIndex, limLite) == null, "Aux tidak boleh kirim ke bus sendiri (loop)")
    val s2 = Ops.removeChannel(s, g1.id)
    check(s2.channel(a1.id)!!.outMask == Out.MASTER, "hapus group -> channel kembali ke Master")
    val s3 = Ops.removeChannel(s, aux.id)
    check(s3.channel(a1.id)!!.sends.isEmpty(), "hapus aux -> send dibersihkan")
    val s4 = Ops.removeChannel(s, a1.id)
    check(s4.channel(a2.id)!!.inserts[0].keySource == -1, "hapus channel key -> keySource dibersihkan")
    check(Ops.removeChannel(s, 0) == s, "Master tidak bisa dihapus")
    val bigScene = Scene("b", listOf(Channel(0, ChKind.MASTER, "M", outMask = 0)) + (1..63).map { Channel(it, ChKind.AUDIO, "c$it") }, 64)
    check(Ops.addChannel(bigScene, ChKind.AUDIO, EditionLimits.PRO, "x") == null, "kapasitas engine 64")
    val fit = Ops.fitToEdition(Project("p", listOf(bigScene), 0, emptyList()), EditionLimits.LITE)
    check(fit.project.scene.count(ChKind.AUDIO) == 8 && fit.trimmed == 55, "fitToEdition memangkas ke 8 (dapat ${fit.project.scene.count(ChKind.AUDIO)}, trimmed ${fit.trimmed})")

    // Undo
    val u = UndoStack(3)
    val p0 = Presets.blankProject("a", cat)
    val p1 = p0.copy(name = "b"); val p2 = p0.copy(name = "c")
    u.push(p0); u.push(p1)
    check(u.undo(p2) == p1 && u.undo(p1) == p0 && u.undo(p0) == null, "undo berurutan")
    check(u.redo(p0) == p1 && u.canRedo, "redo")
    u.push(p0); check(!u.canRedo, "push mengosongkan redo")
    for (i in 0 until 10) u.push(p0)
    var n = 0; var cur = p0; while (true) { cur = u.undo(cur) ?: break; n++ }
    check(n == 3, "undo dibatasi 3 (dapat $n)")

    // Json dasar
    check(Json.stringify(Json.parse("""{"a":[1,2.5,"x\né",true,null]}""")) == """{"a":[1,2.5,"x\n""" + "é" + """",true,null]}""", "Json round trip")
    expectThrows("json terlalu dalam") { Json.parse("[".repeat(200) + "]".repeat(200)) }

    syncMain(args)
    ioMain()
    faderCheck()
    scaleCheck(cat)
    println("$checks pemeriksaan, $fails gagal")
    if (fails > 0) kotlin.system.exitProcess(1)
}
