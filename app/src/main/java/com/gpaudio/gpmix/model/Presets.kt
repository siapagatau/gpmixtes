package com.gpaudio.gpmix.model

import com.gpaudio.gpmix.audio.NativeEngine as N

/** Pembuat InsertSlot berdasarkan nama efek dan nama parameter (tahan terhadap perubahan urutan di C++). */
class FxBuilder(private val catalog: EffectCatalog) {
    private fun typeOf(name: String): EffectInfo =
        catalog.effects.firstOrNull { it.name == name } ?: throw IllegalArgumentException("Efek tidak dikenal: $name")

    /** Kunci override: "Nama" atau "Grup/Nama" (mis. "Band 2/Gain"). */
    fun slot(effect: String, overrides: Map<String, Float> = emptyMap(), key: Int = -1): InsertSlot {
        val info = typeOf(effect)
        val p = info.defaults().toMutableList()
        for ((k, v) in overrides) {
            val i = info.params.indexOfFirst { "${it.group}/${it.name}" == k || (!k.contains('/') && it.name == k) }
            if (i < 0) throw IllegalArgumentException("Parameter tidak dikenal: $effect.$k")
            p[i] = info.params[i].clamp(v)
        }
        return InsertSlot(info.type, p, false, key)
    }
}

object Presets {
    /** Preset channel pabrik: nama (kunci) dan fungsi yang menyusun channel audio dari nol. */
    val channelPresetNames = listOf("Vocal", "Music", "Guest", "TTS", "Chat", "Game")

    fun applyChannelPreset(name: String, base: Channel, fx: FxBuilder): Channel = when (name) {
        "Vocal" -> base.copy(
            hpfOn = true, hpfHz = 90f,
            inserts = listOf(
                fx.slot("Gate", mapOf("Threshold" to -48f, "Range" to -30f)),
                fx.slot("EQ", mapOf("Band 2/On" to 1f, "Band 2/Freq" to 250f, "Band 2/Gain" to -2f,
                    "Band 5/On" to 1f, "Band 5/Freq" to 5000f, "Band 5/Gain" to 2f)),
                fx.slot("Compressor", mapOf("Threshold" to -20f, "Ratio" to 3f, "Makeup" to 3f)),
                fx.slot("De-esser", mapOf("Threshold" to -28f)),
            ),
        )
        "Music" -> base.copy(
            stereo = true, faderDb = -6f,
            inserts = listOf(fx.slot("Limiter", mapOf("Ceiling" to -1f))),
        )
        "Guest" -> base.copy(
            hpfOn = true, hpfHz = 100f,
            inserts = listOf(
                fx.slot("Hum Remover"),
                fx.slot("Compressor", mapOf("Threshold" to -22f, "Ratio" to 3f, "Makeup" to 3f)),
            ),
        )
        "TTS" -> base.copy(
            stereo = false, faderDb = -4f,
            inserts = listOf(fx.slot("Compressor", mapOf("Threshold" to -24f, "Ratio" to 4f, "Makeup" to 2f))),
        )
        "Chat" -> base.copy(
            hpfOn = true, hpfHz = 120f, lpfOn = true, lpfHz = 8000f,
            inserts = listOf(fx.slot("Compressor", mapOf("Threshold" to -22f, "Ratio" to 3f, "Makeup" to 2f))),
        )
        "Game" -> base.copy(
            stereo = true, faderDb = -10f,
            inserts = listOf(fx.slot("Limiter", mapOf("Ceiling" to -1f))),
        )
        else -> base
    }

    private fun audio(id: Int, name: String, color: Int, inKind: Int, inIndex: Int = 0, extra: Int = 0, out: Int = Out.MASTER) =
        Channel(id = id, kind = ChKind.AUDIO, name = name, color = color, inKind = inKind, inIndex = inIndex,
            extraMask = extra, outMask = out)

    private fun master(fx: FxBuilder) = Channel(
        id = 0, kind = ChKind.MASTER, name = "Master", color = 7, outMask = 0,
        inserts = listOf(fx.slot("Limiter", mapOf("Ceiling" to -1f))),
    )

    private fun auxChannel(id: Int, bus: Int, name: String, color: Int, fx: InsertSlot, out: Int) = Channel(
        id = id, kind = ChKind.AUX, name = name, color = color, inKind = N.IN_BUS, inIndex = bus,
        outMask = out, inserts = listOf(fx),
    )

    /** Scene "Singing": vokal + musik + efek Aux (Chorus, Delay, Reverb) dikumpulkan di Group "Master". */
    fun singing(fx: FxBuilder): Scene {
        val g = Channel(id = 1, kind = ChKind.GROUP, name = "Master", color = 6, groupIndex = 0, outMask = Out.MASTER)
        val toG = Out.group(0)
        val mic = applyChannelPreset("Vocal", audio(2, "Mic", 0, N.IN_HW, 0, out = toG), fx)
            .copy(sends = listOf(SendSlot(0, -12f), SendSlot(1, -14f), SendSlot(2, -10f)))
        val music = applyChannelPreset("Music", audio(3, "Music", 2, N.IN_PLAYER, out = Out.MASTER), fx)
            .copy(extraMask = 0)
        val pads = audio(4, "Pads", 3, N.IN_PADS, out = Out.MASTER)
        val chorus = auxChannel(5, 0, "Chorus", 4, fx.slot("Chorus", mapOf("Mix" to 100f)), toG)
        val delay = auxChannel(6, 1, "Delay", 4, fx.slot("Delay", mapOf("Mix" to 100f, "Feedback" to 30f)), toG)
        val reverb = auxChannel(7, 2, "Reverb", 4, fx.slot("Reverb", mapOf("Mix" to 100f)), toG)
        return Scene("Singing", listOf(master(fx), g, mic, music, pads, chorus, delay, reverb), 8)
    }

    /** Scene "Public Chat": mic + musik yang otomatis turun saat bicara (Ducking dengan Key = Mic) + pads + audio aplikasi. */
    fun publicChat(fx: FxBuilder): Scene {
        val mic = applyChannelPreset("Vocal", audio(1, "Mic", 0, N.IN_HW, 0), fx)
        val music = applyChannelPreset("Music", audio(2, "Music", 2, N.IN_PLAYER), fx)
            .copy(inserts = listOf(fx.slot("Ducking", key = 1), fx.slot("Limiter", mapOf("Ceiling" to -1f))))
        val pads = audio(3, "Pads", 3, N.IN_PADS)
        val app = applyChannelPreset("Chat", audio(4, "Chat / Game", 5, N.IN_APP), fx)
        return Scene("Public Chat", listOf(master(fx), mic, music, pads, app), 5)
    }

    /** Scene "Private Chat": hanya suara percakapan, tanpa musik dan efek vokal berat. */
    fun privateChat(fx: FxBuilder): Scene {
        val mic = audio(1, "Mic", 0, N.IN_HW, 0).copy(
            hpfOn = true, hpfHz = 100f,
            inserts = listOf(fx.slot("Compressor", mapOf("Threshold" to -22f, "Ratio" to 3f, "Makeup" to 2f))),
        )
        val app = applyChannelPreset("Chat", audio(2, "Chat", 5, N.IN_APP), fx)
        return Scene("Private Chat", listOf(master(fx), mic, app), 3)
    }

    fun defaultProject(catalog: EffectCatalog, limits: EditionLimits): Project {
        val fx = FxBuilder(catalog)
        val p = Project(
            name = "My Preset",
            scenes = listOf(singing(fx), publicChat(fx), privateChat(fx)),
            activeScene = 0,
            pads = List(N.NUM_PADS) { PadConfig() },
        )
        return Ops.fitToEdition(p, limits).project
    }

    fun blankProject(name: String, catalog: EffectCatalog): Project {
        val fx = FxBuilder(catalog)
        val m = master(fx).copy(inserts = emptyList())
        return Project(name, listOf(Scene("Scene 1", listOf(m), 1)), 0, List(N.NUM_PADS) { PadConfig() })
    }
}
