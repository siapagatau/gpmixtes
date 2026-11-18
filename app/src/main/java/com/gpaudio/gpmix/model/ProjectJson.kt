package com.gpaudio.gpmix.model

/** Simpan/muat project (.gpmixa) dan preset efek sebagai JSON. Pembacaan toleran: nilai rusak diganti default. */
object ProjectJson {
    const val FORMAT = "gpmixa"
    const val VERSION = 1

    // ---------- tulis ----------
    private fun insertMap(i: InsertSlot) = mapOf(
        "type" to i.type, "params" to i.params, "bypass" to i.bypass, "key" to i.keySource,
    )

    private fun sendMap(s: SendSlot) = mapOf("dest" to s.dest, "db" to s.levelDb, "mute" to s.mute, "pre" to s.pre)

    private fun channelMap(c: Channel) = mapOf(
        "id" to c.id, "kind" to c.kind.native, "name" to c.name, "color" to c.color,
        "fader" to c.faderDb, "pan" to c.pan, "trim" to c.trimDb,
        "mute" to c.mute, "solo" to c.solo, "safe" to c.safe, "pol" to c.polarity, "stereo" to c.stereo,
        "hpfOn" to c.hpfOn, "hpfHz" to c.hpfHz, "hpfSlope" to c.hpfSlope,
        "lpfOn" to c.lpfOn, "lpfHz" to c.lpfHz, "lpfSlope" to c.lpfSlope,
        "inKind" to c.inKind, "inIndex" to c.inIndex, "extra" to c.extraMask,
        "out" to c.outMask, "group" to c.groupIndex,
        "inserts" to c.inserts.map(::insertMap), "sends" to c.sends.map(::sendMap),
    )

    fun toJson(p: Project): String = Json.stringify(
        mapOf(
            "format" to FORMAT, "version" to VERSION, "name" to p.name, "activeScene" to p.activeScene,
            "settings" to mapOf(
                "tempo" to p.settings.tempo, "ducking" to p.settings.duckingEnabled,
                "panLaw" to p.settings.panLawDb, "masterMono" to p.settings.masterMono,
                "padsMaster" to p.settings.padsMasterDb, "bufMult" to p.settings.bufferMultiplier,
                "inCh" to p.settings.inputChannels, "retro" to p.settings.retroRecord,
            ),
            "pads" to p.pads.map {
                mapOf("name" to it.name, "file" to it.file, "gain" to it.gainDb, "start" to it.start,
                    "end" to it.end, "choke" to it.choke, "norm" to it.normalize)
            },
            "scenes" to p.scenes.map { s ->
                mapOf("name" to s.name, "nextId" to s.nextId, "channels" to s.channels.map(::channelMap))
            },
        )
    )

    // ---------- baca ----------
    private fun readInsert(m: Map<String, Any?>, catalog: EffectCatalog): InsertSlot? {
        val type = m.int("type", -1)
        val info = catalog.info(type) ?: return null  // efek tak dikenal (mis. dari versi lebih baru): dibuang
        val raw = m.list("params").map { (it as? Number)?.toFloat() }
        val params = info.params.mapIndexed { i, pi -> raw.getOrNull(i)?.let { pi.clamp(it) } ?: pi.def }
        return InsertSlot(type, params, m.bool("bypass", false), m.int("key", -1))
    }

    @Suppress("UNCHECKED_CAST")
    private fun readChannel(m: Map<String, Any?>, catalog: EffectCatalog): Channel {
        val kind = ChKind.fromNative(m.int("kind", 0))
        return Channel(
            id = m.int("id", 0).coerceIn(0, 63),
            kind = kind,
            name = m.str("name", "").take(40),
            color = m.int("color", 0).coerceIn(0, 7),
            faderDb = Db.clampFader(m.flt("fader", 0f)),
            pan = m.flt("pan", 0f).coerceIn(-1f, 1f),
            trimDb = m.flt("trim", 0f).coerceIn(-24f, 24f),
            mute = m.bool("mute", false), solo = m.bool("solo", false), safe = m.bool("safe", false),
            polarity = m.bool("pol", false), stereo = m.bool("stereo", false),
            hpfOn = m.bool("hpfOn", false), hpfHz = m.flt("hpfHz", 80f).coerceIn(20f, 1000f),
            hpfSlope = m.int("hpfSlope", 0).coerceIn(0, 3),
            lpfOn = m.bool("lpfOn", false), lpfHz = m.flt("lpfHz", 18000f).coerceIn(1000f, 20000f),
            lpfSlope = m.int("lpfSlope", 0).coerceIn(0, 3),
            inKind = m.int("inKind", 0).coerceIn(0, 5),
            inIndex = m.int("inIndex", 0).coerceIn(0, 15),
            extraMask = m.int("extra", 0) and 7,
            outMask = m.int("out", 1).coerceIn(0, (1 shl 17) - 1),
            groupIndex = m.int("group", 0).coerceIn(0, 7),
            inserts = m.list("inserts").mapNotNull { (it as? Map<String, Any?>)?.let { x -> readInsert(x, catalog) } }.take(16),
            sends = m.list("sends").mapNotNull {
                (it as? Map<String, Any?>)?.let { x ->
                    SendSlot(x.int("dest", 0).coerceIn(0, 7), x.flt("db", 0f).coerceIn(-60f, 12f), x.bool("mute", false), x.bool("pre", false))
                }
            }.take(16),
        )
    }

    private fun freeIdIn(chans: List<Channel>): Int {
        val used = chans.map { it.id }.toSet()
        return (0 until 64).firstOrNull { it !in used } ?: 0
    }

    /** Lempar IllegalArgumentException dengan pesan jelas bila bukan file GP Mix yang valid. */
    @Suppress("UNCHECKED_CAST")
    fun fromJson(text: String, catalog: EffectCatalog): Project {
        val root = Json.parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("Bukan file project GP Mix")
        if (root.str("format", "") != FORMAT) throw IllegalArgumentException("Bukan file project GP Mix")
        val ver = root.int("version", 0)
        if (ver < 1 || ver > VERSION) throw IllegalArgumentException("Versi project $ver tidak didukung aplikasi ini")
        val st = root.obj("settings") ?: emptyMap()
        val settings = Settings(
            tempo = st.flt("tempo", 120f).coerceIn(20f, 300f),
            duckingEnabled = st.bool("ducking", true),
            panLawDb = st.flt("panLaw", -3f).coerceIn(-6f, -3f),
            masterMono = st.bool("masterMono", false),
            padsMasterDb = st.flt("padsMaster", 0f).coerceIn(-60f, 6f),
            bufferMultiplier = st.int("bufMult", 3).coerceIn(2, 8),
            inputChannels = st.int("inCh", 1).coerceIn(1, 8),
            retroRecord = st.bool("retro", false),
        )
        val pads = root.list("pads").map { it as? Map<String, Any?> }.map { m ->
            if (m == null) PadConfig() else PadConfig(
                name = m.str("name", ""), file = (m["file"] as? String)?.takeIf { it.isNotEmpty() && !it.contains('/') && !it.contains("..") },
                gainDb = m.flt("gain", 0f).coerceIn(-24f, 24f), start = m.flt("start", 0f).coerceIn(0f, 1f),
                end = m.flt("end", 1f).coerceIn(0f, 1f), choke = m.int("choke", 0).coerceIn(0, 8), normalize = m.bool("norm", false),
            )
        }.take(16).let { it + List(16 - it.size) { PadConfig() } }
        val scenes = root.list("scenes").mapNotNull { it as? Map<String, Any?> }.map { sm ->
            val chans = sm.list("channels").mapNotNull { it as? Map<String, Any?> }.map { readChannel(it, catalog) }
                .distinctBy { it.id }
            // Tepat satu Master, selalu di urutan pertama.
            val master = chans.firstOrNull { it.kind == ChKind.MASTER }
                ?: Channel(freeIdIn(chans), ChKind.MASTER, "Master", color = 7, outMask = 0)
            val one = listOf(master) + chans.filter { it.kind != ChKind.MASTER }
            Scene(sm.str("name", "Scene"), one.take(64), sm.int("nextId", 1).coerceIn(1, 64))
        }
        if (scenes.isEmpty()) throw IllegalArgumentException("Project tidak berisi scene")
        return Project(root.str("name", "Project").take(60), scenes, root.int("activeScene", 0).coerceIn(0, scenes.size - 1), pads, settings)
    }

    // ---------- preset efek (plug-in preset) ----------
    data class FxPreset(val effectType: Int, val name: String, val params: List<Float>)

    fun presetToJson(p: FxPreset): String = Json.stringify(
        mapOf("format" to "gpmixfx", "version" to 1, "type" to p.effectType, "name" to p.name, "params" to p.params)
    )

    @Suppress("UNCHECKED_CAST")
    fun presetFromJson(text: String, catalog: EffectCatalog): FxPreset {
        val m = Json.parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("Bukan preset efek")
        if (m.str("format", "") != "gpmixfx") throw IllegalArgumentException("Bukan preset efek GP Mix")
        val info = catalog.info(m.int("type", -1)) ?: throw IllegalArgumentException("Jenis efek tidak dikenal")
        val raw = m.list("params").map { (it as? Number)?.toFloat() }
        return FxPreset(info.type, m.str("name", "Preset").take(60),
            info.params.mapIndexed { i, pi -> raw.getOrNull(i)?.let { pi.clamp(it) } ?: pi.def })
    }
}
