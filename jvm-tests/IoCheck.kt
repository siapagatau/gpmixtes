import com.gpaudio.gpmix.audio.*
import com.gpaudio.gpmix.model.*
import java.io.File

fun ioMain() {
    // WAV 24-bit round trip
    val f = File.createTempFile("gpmix", ".wav")
    val n = 1000
    val data = FloatArray(n * 2) { i -> Math.sin(i * 0.01).toFloat() * 0.9f }
    data[0] = 2f; data[1] = Float.NaN
    Wav24Writer.writeAll(f, data, 2, 44100)
    check(f.length() == 44L + n * 2 * 3, "ukuran file WAV 24-bit")
    val s = WavLoader.load(f)
    check(s.channels == 2 && s.rate == 44100 && s.data.size == n * 2, "header WAV")
    check(Math.abs(s.data[0] - 1f) < 1e-6f && s.data[1] == 0f, "clipping & NaN")
    var maxErr = 0f
    for (i in 2 until n * 2) maxErr = maxOf(maxErr, Math.abs(s.data[i] - data[i]))
    check(maxErr < 2e-7f, "galat kuantisasi 24-bit kecil ($maxErr)")
    // Streaming per bagian
    val w = Wav24Writer(f, 1, 48000)
    w.write(FloatArray(100) { 0.5f }, 100); w.write(FloatArray(50) { -0.5f }, 50); w.close(); w.close()
    val s2 = WavLoader.load(f)
    check(s2.channels == 1 && s2.data.size == 150 && s2.data[0] > 0.49f && s2.data[149] < -0.49f, "tulis berurutan")
    f.delete()
    // File rusak
    var threw = 0
    for (bad in listOf(ByteArray(10), ByteArray(100), "RIFF".toByteArray() + ByteArray(60))) {
        try { WavLoader.parse(bad) } catch (e: IllegalArgumentException) { threw++ }
    }
    check(threw == 3, "WAV rusak ditolak")

    // MIDI
    val ev = ArrayList<String>()
    val p = MidiParser(object : MidiParser.Listener {
        override fun onNoteOn(channel: Int, note: Int, velocity: Int) { ev.add("on $channel $note $velocity") }
        override fun onNoteOff(channel: Int, note: Int) { ev.add("off $channel $note") }
        override fun onControlChange(channel: Int, controller: Int, value: Int) { ev.add("cc $channel $controller $value") }
    })
    fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    val a = bytes(0x90, 36, 100, 0xF8, 37, 0, 0xB1, 7, 64, 8, 65, 0xC0, 5, 0xF0, 1, 2, 3, 0xF7, 0x80, 36, 0)
    p.feed(a, 0, a.size)
    check(ev == listOf("on 0 36 100", "off 0 37", "cc 1 7 64", "cc 1 8 65", "off 0 36"), "MIDI: $ev")
    ev.clear()
    p.feed(bytes(0x90, 40), 0, 2); p.feed(bytes(99), 0, 1)  // pesan terpotong lintas panggilan
    check(ev == listOf("on 0 40 99"), "MIDI terpotong: $ev")
}

fun faderCheck() {
    check(Math.abs(FaderMap.posToDb(0.75f)) < 1e-5f && FaderMap.posToDb(1f) == 6f && FaderMap.posToDb(0f) == -60f, "ujung fader")
    var maxErr = 0f
    var prev = -100f
    var mono = true
    for (i in 0..1000) {
        val p = i / 1000f
        val d = FaderMap.posToDb(p)
        maxErr = maxOf(maxErr, Math.abs(FaderMap.dbToPos(d) - p))
        if (d < prev) mono = false
        prev = d
    }
    check(maxErr < 1e-4f && mono, "fader bolak-balik & monoton ($maxErr)")
    check(FaderMap.posToDb(Float.NaN) == -60f && FaderMap.dbToPos(Float.NaN) == FaderMap.dbToPos(0f), "fader NaN aman")
    check(FaderMap.linToDb(1f) == 0f && FaderMap.linToDb(0f) == -90f, "linToDb")
    check(FaderMap.format(-60f) == "-∞" && FaderMap.format(0f) == "+0.0" && FaderMap.format(-3.25f) == "-3.3", "format dB: ${FaderMap.format(-3.25f)}")
}

fun scaleCheck(cat: EffectCatalog) {
    var n = 0
    for (e in cat.effects) for (p in e.params) {
        if (p.kind != ParamInfo.KIND_FLOAT) continue
        n++
        check(p.max > p.min, "rentang ${e.name}.${p.name}")
        for (t in listOf(0f, 0.25f, 0.5f, 1f)) {
            val v = ParamScale.fromPos(p, t)
            check(v in p.min..p.max, "dalam rentang ${e.name}.${p.name}")
            check(Math.abs(ParamScale.toPos(p, v) - t) < 1e-3f, "bolak-balik ${e.name}.${p.name} t=$t")
        }
        check(ParamScale.fromPos(p, Float.NaN) == p.min, "NaN ${e.name}.${p.name}")
    }
    check(n > 100, "banyak parameter float ($n)")
    val hz = cat.info(0)!!.params.first { it.name == "Freq" }
    check(ParamScale.isLog(hz), "Hz log")
    check(ParamScale.format(hz, 1500f) == "1.50 kHz" && ParamScale.format(hz, 250f) == "250 Hz", "format Hz: ${ParamScale.format(hz, 1500f)} ${ParamScale.format(hz, 250f)}")
    val ch = cat.info(9)!!.params[0]
    check(ParamScale.format(ch, 1f) == "Hall" && ParamScale.format(ch, 99f) == "?", "format pilihan")
}
