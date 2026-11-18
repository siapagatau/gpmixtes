package com.gpaudio.gpmix.audio

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

/** Menerima MIDI dari perangkat USB/Bluetooth. Semua callback dikirim ke thread utama. */
class MidiInput(ctx: Context, private val listener: MidiParser.Listener) {
    private val manager = ctx.applicationContext.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val main = Handler(Looper.getMainLooper())
    private val open = HashMap<Int, Pair<MidiDevice, List<MidiOutputPort>>>()

    @Volatile var deviceNames: List<String> = emptyList()
        private set

    private val mainListener = object : MidiParser.Listener {
        override fun onNoteOn(channel: Int, note: Int, velocity: Int) { main.post { listener.onNoteOn(channel, note, velocity) } }
        override fun onNoteOff(channel: Int, note: Int) { main.post { listener.onNoteOff(channel, note) } }
        override fun onControlChange(channel: Int, controller: Int, value: Int) {
            main.post { listener.onControlChange(channel, controller, value) }
        }
    }

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) { openDevice(device) }
        override fun onDeviceRemoved(device: MidiDeviceInfo) { closeDevice(device.id) }
    }

    val supported: Boolean get() = manager != null

    fun start() {
        val m = manager ?: return
        try {
            m.registerDeviceCallback(callback, main)
            for (d in m.devices) openDevice(d)
        } catch (_: Exception) { /* perangkat tanpa layanan MIDI */ }
    }

    fun stop() {
        try { manager?.unregisterDeviceCallback(callback) } catch (_: Exception) { }
        for (id in open.keys.toList()) closeDevice(id)
    }

    private fun refreshNames(infos: Collection<Pair<MidiDevice, List<MidiOutputPort>>>) {
        deviceNames = infos.map { p ->
            val props = p.first.info.properties
            props.getString(MidiDeviceInfo.PROPERTY_NAME) ?: props.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI"
        }
    }

    private fun openDevice(info: MidiDeviceInfo) {
        val m = manager ?: return
        if (info.outputPortCount <= 0 || open.containsKey(info.id)) return
        m.openDevice(info, { dev ->
            if (dev == null) return@openDevice
            val ports = ArrayList<MidiOutputPort>()
            for (i in 0 until info.outputPortCount) {
                val port = dev.openOutputPort(i) ?: continue
                val parser = MidiParser(mainListener)
                port.connect(object : MidiReceiver() {
                    override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                        parser.feed(msg, offset, count)
                    }
                })
                ports.add(port)
            }
            open[info.id] = Pair(dev, ports)
            refreshNames(open.values)
        }, main)
    }

    private fun closeDevice(id: Int) {
        val p = open.remove(id) ?: return
        for (port in p.second) try { port.close() } catch (_: Exception) { }
        try { p.first.close() } catch (_: Exception) { }
        refreshNames(open.values)
    }
}
