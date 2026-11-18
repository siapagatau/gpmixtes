package com.gpaudio.gpmix.audio

/** Pengurai aliran byte MIDI (running status, pesan real-time disela, SysEx diabaikan). Murni Kotlin. */
class MidiParser(private val listener: Listener) {
    interface Listener {
        fun onNoteOn(channel: Int, note: Int, velocity: Int)
        fun onNoteOff(channel: Int, note: Int)
        fun onControlChange(channel: Int, controller: Int, value: Int)
    }

    private var status = 0
    private val data = IntArray(2)
    private var have = 0
    private var inSysex = false

    fun feed(b: ByteArray, offset: Int, count: Int) {
        for (i in offset until offset + count) feedByte(b[i].toInt() and 0xFF)
    }

    private fun feedByte(v: Int) {
        if (v >= 0xF8) return  // real-time: boleh muncul di mana saja, abaikan
        if (v >= 0x80) {
            if (v == 0xF0) { inSysex = true; status = 0; have = 0; return }
            if (v == 0xF7) { inSysex = false; return }
            inSysex = false
            if (v >= 0xF0) { status = 0; have = 0; return }  // pesan sistem lain: abaikan
            status = v; have = 0
            return
        }
        if (inSysex || status == 0) return
        data[have++] = v
        val need = when (status and 0xF0) { 0xC0, 0xD0 -> 1; else -> 2 }
        if (have < need) return
        have = 0
        val ch = status and 0x0F
        when (status and 0xF0) {
            0x90 -> if (data[1] == 0) listener.onNoteOff(ch, data[0]) else listener.onNoteOn(ch, data[0], data[1])
            0x80 -> listener.onNoteOff(ch, data[0])
            0xB0 -> listener.onControlChange(ch, data[0], data[1])
        }
    }
}
