package com.gpaudio.gpmix.model

import com.gpaudio.gpmix.audio.NativeEngine as N

/** Operasi murni pada model (tanpa Android), semuanya mengembalikan salinan baru. */
object Ops {
    /** Batas efektif = yang lebih kecil antara batas edisi dan kapasitas engine (64 channel, 8 bus, 8 group). */
    fun canAdd(scene: Scene, kind: ChKind, limits: EditionLimits): Boolean {
        if (scene.channels.size >= N.MAX_CHANNELS) return false
        return when (kind) {
            ChKind.AUDIO -> scene.count(ChKind.AUDIO) < limits.audioInputs
            ChKind.AUX -> scene.count(ChKind.AUX) < minOf(limits.aux, N.NUM_BUSES)
            ChKind.GROUP -> scene.count(ChKind.GROUP) < minOf(limits.groups, N.NUM_GROUPS)
            ChKind.MASTER -> false
        }
    }

    private fun freeBus(scene: Scene): Int =
        (0 until N.NUM_BUSES).firstOrNull { b ->
            scene.channels.none { it.kind == ChKind.AUX && it.inIndex == b }
        } ?: -1

    private fun freeGroup(scene: Scene): Int =
        (0 until N.NUM_GROUPS).firstOrNull { g ->
            scene.channels.none { it.kind == ChKind.GROUP && it.groupIndex == g }
        } ?: -1

    private fun freeId(scene: Scene): Int {
        val used = scene.channels.map { it.id }.toSet()
        return (1 until N.MAX_CHANNELS).firstOrNull { it !in used } ?: -1
    }

    fun addChannel(scene: Scene, kind: ChKind, limits: EditionLimits, name: String): Scene? {
        if (!canAdd(scene, kind, limits)) return null
        val id = freeId(scene)
        if (id < 0) return null
        val ch = when (kind) {
            ChKind.AUDIO -> {
                val hw = scene.channels.count { it.kind == ChKind.AUDIO && it.inKind == N.IN_HW }
                Channel(id, kind, name, color = id % 8, inKind = N.IN_HW, inIndex = hw.coerceAtMost(N.MAX_HW_IN - 1))
            }
            ChKind.AUX -> {
                val b = freeBus(scene)
                if (b < 0) return null
                Channel(id, kind, name, color = 4, inKind = N.IN_BUS, inIndex = b)
            }
            ChKind.GROUP -> {
                val g = freeGroup(scene)
                if (g < 0) return null
                Channel(id, kind, name, color = 6, groupIndex = g)
            }
            ChKind.MASTER -> return null
        }
        return scene.copy(channels = scene.channels + ch, nextId = maxOf(scene.nextId, id + 1))
    }

    /** Hapus channel dan bersihkan semua rujukan ke channel itu (grup, bus, key side-chain). */
    fun removeChannel(scene: Scene, id: Int): Scene {
        val gone = scene.channel(id) ?: return scene
        if (gone.kind == ChKind.MASTER) return scene
        val clearMask = when (gone.kind) {
            ChKind.GROUP -> Out.group(gone.groupIndex)
            ChKind.AUX -> Out.bus(gone.inIndex)
            else -> 0
        }
        val rest = scene.channels.filter { it.id != id }.map { c ->
            var m = c.outMask and clearMask.inv()
            if (m == 0 && c.kind != ChKind.MASTER && c.outMask != 0) m = Out.MASTER
            c.copy(
                outMask = m,
                sends = if (gone.kind == ChKind.AUX) c.sends.filter { it.dest != gone.inIndex } else c.sends,
                inserts = c.inserts.map { if (it.keySource == id) it.copy(keySource = -1) else it },
            )
        }
        return scene.copy(channels = rest)
    }

    fun toggleOut(c: Channel, bit: Int): Channel {
        val m = c.outMask xor bit
        return c.copy(outMask = m)
    }

    /** Apakah mengarahkan 'from' ke bus 'bus' membuat loop (Aux yang mengirim ke dirinya sendiri)? */
    fun busLoop(scene: Scene, from: Channel, bus: Int): Boolean =
        from.kind == ChKind.AUX && from.inIndex == bus

    fun addInsert(c: Channel, slot: InsertSlot, limits: EditionLimits): Channel? =
        if (c.inserts.size >= limits.inserts) null else c.copy(inserts = c.inserts + slot)

    fun addSend(c: Channel, scene: Scene, bus: Int, limits: EditionLimits): Channel? {
        if (c.sends.size >= limits.sends || bus !in 0 until N.NUM_BUSES) return null
        if (busLoop(scene, c, bus)) return null
        if (c.sends.any { it.dest == bus }) return null
        return c.copy(sends = c.sends + SendSlot(bus, -12f))
    }

    class FitResult(val project: Project, val trimmed: Int)

    /** Pangkas isi project yang melebihi batas edisi (mis. membuka project Pro di Lite). */
    fun fitToEdition(p: Project, limits: EditionLimits): FitResult {
        var trimmed = 0
        val scenes = p.scenes.map { s ->
            var sc = s
            fun keep(kind: ChKind, max: Int) {
                val over = sc.channels.filter { it.kind == kind }.drop(max)
                for (c in over) { sc = removeChannel(sc, c.id); trimmed++ }
            }
            keep(ChKind.AUDIO, limits.audioInputs)
            keep(ChKind.AUX, minOf(limits.aux, N.NUM_BUSES))
            keep(ChKind.GROUP, minOf(limits.groups, N.NUM_GROUPS))
            sc = sc.copy(channels = sc.channels.map { c ->
                val ins = c.inserts.take(limits.inserts)
                val snd = c.sends.take(limits.sends)
                trimmed += (c.inserts.size - ins.size) + (c.sends.size - snd.size)
                c.copy(inserts = ins, sends = snd)
            })
            sc
        }
        return FitResult(p.copy(scenes = scenes), trimmed)
    }
}

/** Undo/redo berbasis snapshot Project (maksimum 100 langkah). */
class UndoStack(private val limit: Int = 100) {
    private val undo = ArrayDeque<Project>()
    private val redo = ArrayDeque<Project>()

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun push(before: Project) {
        undo.addLast(before)
        while (undo.size > limit) undo.removeFirst()
        redo.clear()
    }

    fun undo(current: Project): Project? {
        val p = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        return p
    }

    fun redo(current: Project): Project? {
        val p = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        return p
    }

    fun clear() { undo.clear(); redo.clear() }
}
