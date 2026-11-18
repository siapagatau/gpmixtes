package com.gpaudio.gpmix.model

/** Batas per edisi (Lite / Plus / Pro), sesuai tabel edisi di manual. */
data class EditionLimits(
    val name: String,
    val audioInputs: Int,
    val aux: Int,
    val groups: Int,
    val inserts: Int,
    val sends: Int,
) {
    companion object {
        val LITE = EditionLimits("Lite", 8, 4, 2, 4, 4)
        val PLUS = EditionLimits("Plus", 32, 8, 8, 8, 8)
        val PRO = EditionLimits("Pro", 256, 128, 32, 16, 16)

        fun byName(n: String): EditionLimits = when (n.lowercase()) {
            "plus" -> PLUS
            "pro" -> PRO
            else -> LITE
        }
    }
}

enum class ChKind(val native: Int) {
    AUDIO(0), AUX(1), GROUP(2), MASTER(3);

    companion object {
        fun fromNative(n: Int): ChKind = values().firstOrNull { it.native == n } ?: AUDIO
    }
}

/** Bit output mask di engine: bit0 = Master, bit1..8 = Group 1..8, bit9..16 = Bus 1..8. */
object Out {
    const val MASTER = 1
    fun group(i: Int) = 1 shl (1 + i)
    fun bus(i: Int) = 1 shl (9 + i)
}

data class InsertSlot(
    val type: Int,
    val params: List<Float>,
    val bypass: Boolean = false,
    /** -1 = sinyal sendiri, selain itu id channel sumber Key (side chain). */
    val keySource: Int = -1,
)

data class SendSlot(
    /** Bus 0..7. */
    val dest: Int,
    val levelDb: Float = 0f,
    val mute: Boolean = false,
    val pre: Boolean = false,
)

data class Channel(
    val id: Int,
    val kind: ChKind,
    val name: String,
    val color: Int = 0,
    val faderDb: Float = 0f,
    val pan: Float = 0f,
    val trimDb: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
    val safe: Boolean = false,
    val polarity: Boolean = false,
    val stereo: Boolean = false,
    val hpfOn: Boolean = false,
    val hpfHz: Float = 80f,
    val hpfSlope: Int = 0,
    val lpfOn: Boolean = false,
    val lpfHz: Float = 18000f,
    val lpfSlope: Int = 0,
    /** Jenis input (NativeEngine.IN_*), indeks (input HW 0..7 / bus 0..7), dan sumber tambahan (SRC_*). */
    val inKind: Int = 0,
    val inIndex: Int = 0,
    val extraMask: Int = 0,
    val outMask: Int = Out.MASTER,
    val groupIndex: Int = 0,
    val inserts: List<InsertSlot> = emptyList(),
    val sends: List<SendSlot> = emptyList(),
)

data class PadConfig(
    val name: String = "",
    /** Nama file di penyimpanan internal aplikasi (bukan URI), null = pad kosong. */
    val file: String? = null,
    val gainDb: Float = 0f,
    val start: Float = 0f,
    val end: Float = 1f,
    val choke: Int = 0,
    val normalize: Boolean = false,
)

data class Scene(
    val name: String,
    val channels: List<Channel>,
    val nextId: Int,
) {
    fun channel(id: Int): Channel? = channels.firstOrNull { it.id == id }
    val master: Channel? get() = channels.firstOrNull { it.kind == ChKind.MASTER }
    fun count(kind: ChKind) = channels.count { it.kind == kind }

    fun update(id: Int, f: (Channel) -> Channel): Scene =
        copy(channels = channels.map { if (it.id == id) f(it) else it })
}

data class Settings(
    val tempo: Float = 120f,
    val duckingEnabled: Boolean = true,
    val panLawDb: Float = -3f,
    val masterMono: Boolean = false,
    val padsMasterDb: Float = 0f,
    val bufferMultiplier: Int = 3,
    val inputChannels: Int = 1,
    val retroRecord: Boolean = false,
)

data class Project(
    val name: String,
    val scenes: List<Scene>,
    val activeScene: Int,
    val pads: List<PadConfig>,
    val settings: Settings = Settings(),
) {
    val scene: Scene get() = scenes[activeScene.coerceIn(0, scenes.size - 1)]
    fun withScene(s: Scene): Project {
        val i = activeScene.coerceIn(0, scenes.size - 1)
        return copy(scenes = scenes.mapIndexed { k, old -> if (k == i) s else old })
    }
}

/** Utilitas konversi nilai. */
object Db {
    const val FADER_MIN = -60f
    const val FADER_MAX = 6f
    fun clampFader(v: Float) = if (v.isNaN()) 0f else v.coerceIn(FADER_MIN, FADER_MAX)
}

/** Ubah satu slot insert; slot di luar jangkauan = tanpa perubahan. */
fun Channel.withInsert(slot: Int, f: (InsertSlot) -> InsertSlot): Channel {
    val cur = inserts.getOrNull(slot) ?: return this
    return copy(inserts = inserts.toMutableList().also { it[slot] = f(cur) })
}
