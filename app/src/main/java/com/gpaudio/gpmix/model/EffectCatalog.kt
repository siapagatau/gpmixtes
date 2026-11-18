package com.gpaudio.gpmix.model

/** Deskripsi satu parameter efek, dibaca dari engine C++ (sumber kebenaran tunggal). */
data class ParamInfo(
    val group: String,
    val name: String,
    val kind: Int,          // 0 = float, 1 = toggle, 2 = pilihan
    val min: Float,
    val max: Float,
    val def: Float,
    val unit: String,
    val choices: List<String>,
) {
    val isToggle get() = kind == KIND_TOGGLE
    val isChoice get() = kind == KIND_CHOICE

    fun clamp(v: Float): Float = when {
        v.isNaN() -> def
        v < min -> min
        v > max -> max
        else -> v
    }

    companion object {
        const val KIND_FLOAT = 0
        const val KIND_TOGGLE = 1
        const val KIND_CHOICE = 2
    }
}

data class EffectInfo(
    val type: Int,
    val name: String,
    val category: String,
    val usesKey: Boolean,
    val params: List<ParamInfo>,
) {
    fun defaults(): List<Float> = params.map { it.def }
}

class EffectCatalog(val effects: List<EffectInfo>) {
    fun info(type: Int): EffectInfo? = effects.getOrNull(type)?.takeIf { it.type == type }
    val categories: List<String> get() = effects.map { it.category }.distinct()

    companion object {
        /** Format: baris "E|tipe|nama|kategori|usesKey" diikuti baris "P|grup|nama|jenis|min|max|def|unit|pilihan". */
        fun parse(text: String): EffectCatalog {
            val out = ArrayList<EffectInfo>()
            var cur: EffectInfo? = null
            val params = ArrayList<ParamInfo>()
            fun flush() {
                cur?.let { out.add(it.copy(params = params.toList())) }
                params.clear()
            }
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                val f = line.split('|')
                when (f[0]) {
                    "E" -> {
                        flush()
                        if (f.size < 5) throw IllegalArgumentException("Baris efek rusak: $line")
                        cur = EffectInfo(f[1].toInt(), f[2], f[3], f[4] == "1", emptyList())
                    }
                    "P" -> {
                        if (f.size < 8 || cur == null) throw IllegalArgumentException("Baris parameter rusak: $line")
                        val choices = if (f.size > 8 && f[8].isNotEmpty()) f[8].split(',') else emptyList()
                        params.add(
                            ParamInfo(f[1], f[2], f[3].toInt(), f[4].toFloat(), f[5].toFloat(), f[6].toFloat(), f[7], choices)
                        )
                    }
                }
            }
            flush()
            return EffectCatalog(out)
        }
    }
}
