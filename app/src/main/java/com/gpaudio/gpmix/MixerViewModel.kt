package com.gpaudio.gpmix

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.gpaudio.gpmix.audio.AudioDecoder
import com.gpaudio.gpmix.audio.AudioService
import com.gpaudio.gpmix.audio.MidiInput
import com.gpaudio.gpmix.audio.MidiParser
import com.gpaudio.gpmix.audio.NativeEngine as N
import com.gpaudio.gpmix.audio.Recorder
import com.gpaudio.gpmix.audio.Wav24Writer
import com.gpaudio.gpmix.audio.WavLoader
import com.gpaudio.gpmix.engine.EngineSync
import com.gpaudio.gpmix.engine.NativeApi
import com.gpaudio.gpmix.model.ChKind
import com.gpaudio.gpmix.model.Channel
import com.gpaudio.gpmix.model.EditionLimits
import com.gpaudio.gpmix.model.EffectCatalog
import com.gpaudio.gpmix.model.FaderMap
import com.gpaudio.gpmix.model.InsertSlot
import com.gpaudio.gpmix.model.Ops
import com.gpaudio.gpmix.model.PadConfig
import com.gpaudio.gpmix.model.Presets
import com.gpaudio.gpmix.model.FxBuilder
import com.gpaudio.gpmix.model.Project
import com.gpaudio.gpmix.model.ProjectJson
import com.gpaudio.gpmix.model.Scene
import com.gpaudio.gpmix.model.Settings
import com.gpaudio.gpmix.model.UndoStack
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

data class EngineInfo(
    val sampleRate: Int = 0,
    val burst: Int = 0,
    val bufferFrames: Int = 0,
    val inputChannels: Int = 0,
    val latencyMs: Float = 0f,
    val xruns: Int = 0,
    val running: Boolean = false,
)

data class DeviceItem(val id: Int, val name: String, val channels: Int)

/** Pesan satu-kali untuk UI: kunci string + argumen opsional. */
data class UiMessage(val resId: Int, val arg: String? = null)

enum class MidiTarget { NONE, PAD, FADER }

/** Pusat logika aplikasi. Semua fungsi publik dipanggil dari thread utama. */
class MixerViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication<Application>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    val prefs = Prefs(app)

    val limits: EditionLimits = EditionLimits.byName(BuildConfig.EDITION)

    // ---- status engine ----
    var engineReady by mutableStateOf(false); private set
    var engineError by mutableStateOf<String?>(null); private set
    var catalog: EffectCatalog = EffectCatalog(emptyList()); private set
    var running by mutableStateOf(false); private set
    var info by mutableStateOf(EngineInfo()); private set
    var meters by mutableStateOf(FloatArray(3 * N.MAX_CHANNELS)); private set
    var clipped by mutableStateOf<Set<Int>>(emptySet()); private set
    var message by mutableStateOf<UiMessage?>(null)
    var theme by mutableStateOf(prefs.theme); private set
    var inputDeviceId by mutableStateOf(prefs.inputDeviceId); private set
    var outputDeviceId by mutableStateOf(prefs.outputDeviceId); private set
    var midiMappingCount by mutableStateOf(0); private set

    // ---- proyek ----
    var project by mutableStateOf(Project("", listOf(Scene("", emptyList(), 1)), 0, emptyList())); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var projectFiles by mutableStateOf<List<String>>(emptyList()); private set
    private val undo = UndoStack(100)
    private val sync = EngineSync(NativeApi)
    private val projectDir = File(app.filesDir, "projects")
    private val padDir = File(app.filesDir, "pads")
    val recordDir = File(app.filesDir, "recordings")
    private var dirty = false

    // ---- UI ----
    var selectedChannel by mutableStateOf<Int?>(null)
    var editingInsert by mutableStateOf<Pair<Int, Int>?>(null)
    var fxGainReduction by mutableStateOf(0f); private set
    var midiLearn by mutableStateOf(MidiTarget.NONE); private set
    var midiLearnArg by mutableStateOf(0); private set
    var midiDevices by mutableStateOf<List<String>>(emptyList()); private set

    // ---- perangkat ----
    var inputDevices by mutableStateOf<List<DeviceItem>>(emptyList()); private set
    var outputDevices by mutableStateOf<List<DeviceItem>>(emptyList()); private set

    // ---- pads / player / rekam ----
    var padPlaying by mutableStateOf(0); private set
    var padLoading by mutableStateOf<Int?>(null); private set
    var playerTitle by mutableStateOf<String?>(null); private set
    var playerFrames by mutableStateOf(0L); private set
    var playerRate by mutableStateOf(48000); private set
    var playerPos by mutableStateOf(0L); private set
    var playerPlaying by mutableStateOf(false); private set
    var playerLoop by mutableStateOf(false); private set
    var playerVolumeDb by mutableStateOf(0f); private set
    var playerBusy by mutableStateOf(false); private set
    var recSeconds by mutableStateOf(0f); private set
    var recording by mutableStateOf(false); private set
    var recSource by mutableStateOf(-1)
    var recordings by mutableStateOf<List<File>>(emptyList()); private set
    var captureActive by mutableStateOf(false); private set
    val recorder = Recorder(recordDir)

    private var midi: MidiInput? = null
    private var midiMap = HashMap<String, String>()
    private var disp = FloatArray(3 * N.MAX_CHANNELS)
    private var ticking = false
    private var tickCount = 0

    private val autosave = Runnable { saveNow() }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!ticking) return
            if (engineReady && running) pollEngine()
            main.postDelayed(this, 33)
        }
    }

    init {
        projectDir.mkdirs(); padDir.mkdirs(); recordDir.mkdirs()
        if (!N.ensureLoaded()) {
            engineError = N.loadError ?: "native library"
        } else {
            try {
                catalog = EffectCatalog.parse(N.describeEffects())
                if (catalog.effects.isEmpty()) throw IllegalStateException("katalog efek kosong")
                loadInitialProject()
                engineReady = true
                if (N.info().getOrNull(6)?.let { it > 0.5f } == true) { running = true; startTicking() }
            } catch (t: Throwable) {
                engineError = t.message ?: t.toString()
            }
        }
        loadMidiMap()
        refreshDevices()
        refreshProjectList()
        recordings = recorder.list()
        startMidi()
    }

    // =====================================================================
    // Proyek
    // =====================================================================
    private fun loadInitialProject() {
        val last = prefs.lastProject
        var p: Project? = null
        if (last.isNotEmpty()) p = readProjectFile(File(projectDir, safeName(last) + EXT))
        if (p == null) p = Presets.defaultProject(catalog, limits).also { prefs.lastProject = it.name }
        installProject(p, clearUndo = true)
    }

    private fun readProjectFile(f: File): Project? = try {
        if (!f.isFile || f.length() > 8L shl 20) null
        else ProjectJson.fromJson(f.readText(), catalog).let { Ops.fitToEdition(it, limits).project }
    } catch (_: Exception) { null }

    private fun installProject(p: Project, clearUndo: Boolean) {
        project = p
        if (clearUndo) { undo.clear(); refreshUndoFlags() }
        sync.apply(p)
        N.setPadsMasterDb(p.settings.padsMasterDb)
        loadAllPads(p)
        selectedChannel = null; editingInsert = null
        if (running) applyRetro()
    }

    private fun refreshUndoFlags() { canUndo = undo.canUndo; canRedo = undo.canRedo }

    private fun safeName(n: String): String {
        val s = n.trim().replace(Regex("[^\\p{L}\\p{N} _.-]"), "_").take(60)
        return if (s.isEmpty() || s.startsWith(".")) "Project" else s
    }

    /** Ubah project dengan satu langkah undo. */
    fun edit(f: (Project) -> Project) {
        val before = project
        val after = f(before)
        if (after == before) return
        undo.push(before)
        refreshUndoFlags()
        applyProject(after)
    }

    /** Ubah project tanpa menambah langkah undo (dipakai selama gestur; panggil beginGesture() lebih dulu). */
    fun change(f: (Project) -> Project) {
        val after = f(project)
        if (after != project) applyProject(after)
    }

    fun beginGesture() { undo.push(project); refreshUndoFlags() }

    private fun applyProject(p: Project) {
        project = p
        sync.apply(p)
        markDirty()
    }

    fun editScene(f: (Scene) -> Scene) = edit { it.withScene(f(it.scene)) }
    fun changeScene(f: (Scene) -> Scene) = change { it.withScene(f(it.scene)) }
    fun editChannel(id: Int, f: (Channel) -> Channel) = editScene { it.update(id, f) }
    fun changeChannel(id: Int, f: (Channel) -> Channel) = changeScene { it.update(id, f) }

    fun undo() { undo.undo(project)?.let { applyProject(it); refreshUndoFlags() } }
    fun redo() { undo.redo(project)?.let { applyProject(it); refreshUndoFlags() } }

    fun selectScene(i: Int) {
        if (i == project.activeScene || i !in project.scenes.indices) return
        edit { it.copy(activeScene = i) }
        selectedChannel = null; editingInsert = null
    }

    fun addScene(name: String) {
        if (project.scenes.size >= 16) return
        val m = project.scene.master ?: return
        val s = Scene(name.trim().ifEmpty { "Scene ${project.scenes.size + 1}" }.take(40), listOf(m.copy(inserts = m.inserts)), project.scene.nextId.coerceAtLeast(1))
        edit { it.copy(scenes = it.scenes + s, activeScene = it.scenes.size) }
    }

    fun duplicateScene() {
        if (project.scenes.size >= 16) return
        val s = project.scene
        edit { it.copy(scenes = it.scenes + s.copy(name = (s.name + " 2").take(40)), activeScene = it.scenes.size) }
    }

    fun renameScene(name: String) = editScene { it.copy(name = name.trim().take(40).ifEmpty { it.name }) }

    fun deleteScene() {
        if (project.scenes.size <= 1) return
        edit { p ->
            val i = p.activeScene
            p.copy(scenes = p.scenes.filterIndexed { k, _ -> k != i }, activeScene = (i - 1).coerceAtLeast(0))
        }
    }

    fun addChannel(kind: ChKind): Boolean {
        val base = when (kind) { ChKind.AUDIO -> "Input"; ChKind.AUX -> "Aux"; ChKind.GROUP -> "Group"; else -> "" }
        val s = project.scene
        val n = s.count(kind) + 1
        val ns = Ops.addChannel(s, kind, limits, "$base $n")
        if (ns == null) { say(R.string.msg_limit_reached, limits.name); return false }
        edit { it.withScene(ns) }
        return true
    }

    fun removeChannel(id: Int) {
        edit { it.withScene(Ops.removeChannel(it.scene, id)) }
        if (selectedChannel == id) selectedChannel = null
        if (editingInsert?.first == id) editingInsert = null
    }

    fun applyChannelPreset(id: Int, preset: String) {
        val fx = FxBuilder(catalog)
        editChannel(id) { c ->
            val p = Presets.applyChannelPreset(preset, c.copy(inserts = emptyList()), fx)
            p.copy(inserts = p.inserts.take(limits.inserts), name = c.name, id = c.id, kind = c.kind,
                inKind = c.inKind, inIndex = c.inIndex, outMask = c.outMask, sends = c.sends)
        }
    }

    fun addInsert(id: Int, type: Int) {
        val info = catalog.info(type) ?: return
        val c = project.scene.channel(id) ?: return
        val updated = Ops.addInsert(c, InsertSlot(type, info.defaults()), limits)
        if (updated == null) { say(R.string.msg_limit_reached, limits.name); return }
        editChannel(id) { updated }
    }

    fun removeInsert(id: Int, slot: Int) {
        editChannel(id) { c -> c.copy(inserts = c.inserts.filterIndexed { i, _ -> i != slot }) }
        if (editingInsert == id to slot) editingInsert = null
    }

    fun moveInsert(id: Int, slot: Int, delta: Int) {
        editChannel(id) { c ->
            val t = slot + delta
            if (slot !in c.inserts.indices || t !in c.inserts.indices) c
            else c.copy(inserts = c.inserts.toMutableList().also { val x = it.removeAt(slot); it.add(t, x) })
        }
        editingInsert = null
    }

    fun setFxParam(id: Int, slot: Int, index: Int, value: Float) {
        changeChannel(id) { c ->
            val ins = c.inserts.getOrNull(slot) ?: return@changeChannel c
            val info = catalog.info(ins.type) ?: return@changeChannel c
            val pi = info.params.getOrNull(index) ?: return@changeChannel c
            c.copy(inserts = c.inserts.toMutableList().also {
                it[slot] = ins.copy(params = ins.params.toMutableList().also { p -> p[index] = pi.clamp(value) })
            })
        }
    }

    fun savePreset(): String? {
        val sel = editingInsert ?: return null
        val ins = project.scene.channel(sel.first)?.inserts?.getOrNull(sel.second) ?: return null
        val info = catalog.info(ins.type) ?: return null
        return ProjectJson.presetToJson(ProjectJson.FxPreset(ins.type, info.name, ins.params))
    }

    fun loadPreset(json: String) {
        val sel = editingInsert ?: return
        try {
            val pr = ProjectJson.presetFromJson(json, catalog)
            val cur = project.scene.channel(sel.first)?.inserts?.getOrNull(sel.second) ?: return
            if (cur.type != pr.effectType) { say(R.string.msg_preset_wrong_type); return }
            editChannel(sel.first) { c ->
                c.copy(inserts = c.inserts.toMutableList().also { it[sel.second] = cur.copy(params = pr.params) })
            }
        } catch (e: Exception) { say(R.string.msg_preset_invalid) }
    }

    // ---- penyimpanan ----
    private fun markDirty() {
        dirty = true
        main.removeCallbacks(autosave)
        main.postDelayed(autosave, 1500)
    }


    fun saveNow() {
        if (!engineReady || !dirty) return
        dirty = false
        val p = project
        val text = ProjectJson.toJson(p)
        prefs.lastProject = p.name
        val f = File(projectDir, safeName(p.name) + EXT)
        io.execute {
            try {
                val tmp = File(projectDir, f.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
            } catch (_: Exception) { main.post { say(R.string.msg_save_failed) } }
        }
        main.postDelayed({ refreshProjectList() }, 300)
    }

    fun refreshProjectList() {
        projectFiles = (projectDir.listFiles { x -> x.isFile && x.name.endsWith(EXT) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }.map { it.name.removeSuffix(EXT) }
    }

    fun newProject(name: String) {
        saveNow()
        val p = Presets.blankProject(name.trim().ifEmpty { "Project" }.take(60), catalog)
        prefs.lastProject = p.name
        installProject(p, true)
        dirty = true; saveNow()
    }

    fun openProject(name: String) {
        saveNow()
        val p = readProjectFile(File(projectDir, safeName(name) + EXT))
        if (p == null) { say(R.string.msg_open_failed); return }
        prefs.lastProject = p.name
        installProject(p, true)
    }

    fun renameProject(name: String) {
        val n = name.trim().take(60)
        if (n.isEmpty()) return
        val old = File(projectDir, safeName(project.name) + EXT)
        edit { it.copy(name = n) }
        old.delete()
        dirty = true; saveNow()
    }

    fun deleteProject(name: String) {
        File(projectDir, safeName(name) + EXT).delete()
        refreshProjectList()
    }

    fun resetToDefault() {
        edit { Presets.defaultProject(catalog, limits).copy(name = it.name, pads = it.pads) }
    }

    /** Teks project untuk diekspor lewat SAF. */
    fun exportText(): String = ProjectJson.toJson(project)

    fun importText(text: String) {
        try {
            val p = Ops.fitToEdition(ProjectJson.fromJson(text, catalog), limits)
            saveNow()
            installProject(p.project, true)
            prefs.lastProject = p.project.name
            dirty = true; saveNow()
            if (p.trimmed > 0) say(R.string.msg_trimmed, p.trimmed.toString())
            else say(R.string.msg_imported)
        } catch (e: Exception) {
            say(R.string.msg_import_failed, e.message ?: "")
        }
    }

    // =====================================================================
    // Engine audio
    // =====================================================================
    fun startEngine(): Boolean {
        if (!engineReady) return false
        val s = project.settings
        val rc = N.start(prefs.inputDeviceId, prefs.outputDeviceId, s.inputChannels, s.bufferMultiplier)
        if (rc == 1) { say(R.string.msg_output_failed); running = false; return false }
        if (rc == 2) say(R.string.msg_input_failed)
        running = true
        applyRetro()
        startTicking()
        return true
    }

    fun stopEngine() {
        stopRecording()
        N.stop()
        running = false
        info = EngineInfo()
    }

    private fun applyRetro() { N.retroEnable(project.settings.retroRecord) }

    fun setSettings(f: (Settings) -> Settings) {
        val old = project.settings
        edit { it.copy(settings = f(it.settings)) }
        val n = project.settings
        if (running && (old.bufferMultiplier != n.bufferMultiplier || old.inputChannels != n.inputChannels)) restartEngine()
        if (old.retroRecord != n.retroRecord && running) applyRetro()
    }

    fun restartEngine() { if (running) { N.stop(); startEngine() } }

    fun setInputDevice(id: Int) { prefs.inputDeviceId = id; inputDeviceId = id; restartEngine() }
    fun setOutputDevice(id: Int) { prefs.outputDeviceId = id; outputDeviceId = id; restartEngine() }

    fun refreshDevices() {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        fun name(d: AudioDeviceInfo): String {
            val p = d.productName?.toString().orEmpty()
            val t = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Mic"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
                else -> ""
            }
            return listOf(p, t).filter { it.isNotEmpty() }.distinct().joinToString(" · ").ifEmpty { "#${d.id}" }
        }
        fun maxCh(d: AudioDeviceInfo) = (d.channelCounts.maxOrNull() ?: 2).coerceIn(1, N.MAX_HW_IN)
        inputDevices = am.getDevices(AudioManager.GET_DEVICES_INPUTS).map { DeviceItem(it.id, name(it), maxCh(it)) }
        outputDevices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { DeviceItem(it.id, name(it), 2) }
    }

    // ---- polling meter dan status ----
    fun startTicking() {
        if (ticking) return
        ticking = true
        main.post(tick)
    }

    fun stopTicking() { ticking = false; main.removeCallbacks(tick) }

    private fun pollEngine() {
        tickCount++
        val raw = FloatArray(3 * N.MAX_CHANNELS)
        N.readMeters(raw)
        var newClip: MutableSet<Int>? = null
        for (i in 0 until 2 * N.MAX_CHANNELS) {
            val v = raw[i]
            disp[i] = if (v > disp[i]) v else disp[i] * 0.86f
            if (v >= 0.999f) {
                val ch = i / 2
                if (newClip == null) newClip = clipped.toMutableSet()
                newClip.add(ch)
            }
        }
        for (i in 2 * N.MAX_CHANNELS until 3 * N.MAX_CHANNELS) disp[i] = raw[i]
        meters = disp.copyOf()
        if (newClip != null && newClip.size != clipped.size) clipped = newClip

        val padMask = N.padPlayingMask()
        if (padMask != padPlaying) padPlaying = padMask
        if (playerFrames > 0) {
            playerPos = N.playerPos()
            val pl = N.playerPlaying()
            if (pl != playerPlaying) playerPlaying = pl
        }
        if (recorder.recording || recording) {
            recSeconds = recorder.seconds
            if (recording && !recorder.recording) { recording = false; recordings = recorder.list(); recorder.error?.let { say(R.string.msg_rec_error, it) } }
        }
        val ed = editingInsert
        if (ed != null) fxGainReduction = N.fxGainReduction(ed.first, ed.second)
        if (tickCount % 15 == 0) {
            val a = N.info()
            if (a.size >= 7) {
                info = EngineInfo(a[0].toInt(), a[1].toInt(), a[2].toInt(), a[3].toInt(), a[4], a[5].toInt(), a[6] > 0.5f)
                if (a[6] < 0.5f && running) { running = false; stopRecording() }
            }
            val cap = AudioService.captureActive
            if (cap != captureActive) {
                captureActive = cap
                if (!cap) AudioService.captureError?.let { say(R.string.msg_capture_error, it) }
            }
            if (project.settings.retroRecord && !N.retroEnabled()) N.retroEnable(true)  // engine membuka ulang stream
            if (midi != null) midiDevices = midi!!.deviceNames
        }
    }

    fun changeTheme(t: String) { prefs.theme = t; theme = t }

    fun clearClip() { clipped = emptySet() }

    // =====================================================================
    // Sample Pads
    // =====================================================================
    private fun padFile(cfg: PadConfig): File? = cfg.file?.let { File(padDir, it) }

    private fun loadAllPads(p: Project) {
        for (i in 0 until N.NUM_PADS) {
            val cfg = p.pads.getOrNull(i) ?: PadConfig()
            pushPadParams(i, cfg)
            val f = padFile(cfg)
            if (f == null || !f.isFile) { N.clearPad(i); continue }
            io.execute {
                try {
                    val w = WavLoader.load(f)
                    N.loadPad(i, w.data, w.channels, w.rate)
                } catch (_: Exception) { main.post { say(R.string.msg_pad_missing, cfg.name) } }
            }
        }
    }

    private fun pushPadParams(i: Int, c: PadConfig) {
        N.setPadParam(i, N.PP_GAIN_DB, c.gainDb)
        N.setPadParam(i, N.PP_START, c.start)
        N.setPadParam(i, N.PP_END, c.end)
        N.setPadParam(i, N.PP_CHOKE, c.choke.toFloat())
        N.setPadParam(i, N.PP_NORMALIZE, if (c.normalize) 1f else 0f)
    }

    fun triggerPad(i: Int, velocity: Float = 1f) {
        if (i !in 0 until N.NUM_PADS) return
        N.triggerPad(i, velocity.coerceIn(0.05f, 1f))
    }

    fun stopPads() = N.stopPads()

    fun editPad(i: Int, push: Boolean = true, f: (PadConfig) -> PadConfig) {
        if (i !in 0 until N.NUM_PADS) return
        val fn = { p: Project -> p.copy(pads = p.pads.mapIndexed { k, c -> if (k == i) f(c) else c }) }
        if (push) edit(fn) else change(fn)
        pushPadParams(i, project.pads[i])
    }

    fun clearPad(i: Int) {
        val old = project.pads.getOrNull(i)?.file
        N.clearPad(i)
        editPad(i) { PadConfig() }
        if (old != null && project.pads.none { it.file == old }) File(padDir, old).delete()
    }

    fun loadPadFromUri(i: Int, uri: Uri) {
        if (i !in 0 until N.NUM_PADS) return
        padLoading = i
        io.execute {
            try {
                val d = AudioDecoder.decode(ctx, uri, AudioDecoder.MAX_PAD_SECONDS.toLong() * 48000 * 2 * 2)
                val (data, ch) = AudioDecoder.toPadFloat(d)
                val name = "pad-" + UUID.randomUUID().toString().take(12) + ".wav"
                Wav24Writer.writeAll(File(padDir, name), data, ch, d.rate)
                val title = displayName(uri).substringBeforeLast('.').take(24)
                main.post {
                    val old = project.pads.getOrNull(i)?.file
                    N.loadPad(i, data, ch, d.rate)
                    editPad(i) { it.copy(name = title, file = name, start = 0f, end = 1f) }
                    if (old != null && project.pads.none { it.file == old }) File(padDir, old).delete()
                    padLoading = null
                }
            } catch (e: AudioDecoder.TooLongException) {
                main.post { padLoading = null; say(R.string.msg_pad_too_long, AudioDecoder.MAX_PAD_SECONDS.toString()) }
            } catch (e: Exception) {
                main.post { padLoading = null; say(R.string.msg_decode_failed, e.message ?: "") }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) { val n = c.getString(0); if (!n.isNullOrEmpty()) return n }
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment ?: "Audio"
    }

    // =====================================================================
    // Player
    // =====================================================================
    fun loadPlayerFromUri(uri: Uri) {
        playerBusy = true
        io.execute {
            try {
                val d = AudioDecoder.decode(ctx, uri, AudioDecoder.MAX_PLAYER_BYTES)
                val st = AudioDecoder.toStereo(d)
                val title = displayName(uri).take(60)
                main.post {
                    N.playerPlay(false)
                    N.loadPlayer(st, d.frames, d.rate)
                    N.playerLoop(playerLoop)
                    N.playerVolumeDb(playerVolumeDb)
                    playerTitle = title; playerFrames = d.frames.toLong(); playerRate = d.rate
                    playerPos = 0; playerPlaying = false; playerBusy = false
                }
            } catch (e: AudioDecoder.TooLongException) {
                main.post { playerBusy = false; say(R.string.msg_player_too_long) }
            } catch (e: Throwable) {
                main.post { playerBusy = false; say(R.string.msg_decode_failed, e.message ?: "") }
            }
        }
    }

    fun clearPlayer() {
        N.playerPlay(false); N.clearPlayer()
        playerTitle = null; playerFrames = 0; playerPos = 0; playerPlaying = false
    }

    fun togglePlay() {
        if (playerFrames <= 0) return
        val on = !playerPlaying
        if (on && playerPos >= playerFrames - 1 && !playerLoop) { N.playerSeek(0); playerPos = 0 }
        N.playerPlay(on)
        playerPlaying = on
    }

    fun seekPlayer(frac: Float) {
        if (playerFrames <= 0) return
        val f = (frac.coerceIn(0f, 1f) * playerFrames).toLong()
        N.playerSeek(f); playerPos = f
    }

    fun updatePlayerLoop(on: Boolean) { playerLoop = on; N.playerLoop(on) }
    fun setPlayerVolume(db: Float) { playerVolumeDb = db.coerceIn(-60f, 6f); N.playerVolumeDb(playerVolumeDb) }

    // =====================================================================
    // Rekam
    // =====================================================================
    fun startRecording() {
        if (!running) { say(R.string.msg_start_first); return }
        val rate = if (info.sampleRate > 0) info.sampleRate else 48000
        if (recorder.start(recSource, rate)) { recording = true; recSeconds = 0f }
        else say(R.string.msg_rec_error, recorder.error ?: "")
    }

    fun stopRecording() {
        if (!recording && !recorder.recording) return
        val f = recorder.stop()
        recording = false
        recordings = recorder.list()
        if (f != null) say(R.string.msg_rec_saved, f.name)
    }

    fun saveRetro() {
        if (!running || !project.settings.retroRecord) { say(R.string.msg_retro_off); return }
        io.execute {
            val f = try { recorder.saveRetro() } catch (e: Exception) { null }
            main.post {
                recordings = recorder.list()
                if (f != null) say(R.string.msg_rec_saved, f.name) else say(R.string.msg_retro_empty)
            }
        }
    }

    fun deleteRecording(f: File) {
        if (f.parentFile == recordDir) f.delete()
        recordings = recorder.list()
    }

    // =====================================================================
    // Tangkap audio aplikasi
    // =====================================================================
    fun onCaptureConsent(resultCode: Int, data: Intent?) {
        if (data == null) { say(R.string.msg_capture_denied); return }
        val i = Intent(ctx, AudioService::class.java).setAction(AudioService.ACTION_CAPTURE)
            .putExtra(AudioService.EXTRA_RESULT_CODE, resultCode).putExtra(AudioService.EXTRA_DATA, data)
        ContextCompat.startForegroundService(ctx, i)
    }

    fun stopCapture() {
        ctx.startService(Intent(ctx, AudioService::class.java).setAction(AudioService.ACTION_STOP_CAPTURE))
    }

    // =====================================================================
    // MIDI
    // =====================================================================
    private fun startMidi() {
        val m = MidiInput(ctx, object : MidiParser.Listener {
            override fun onNoteOn(channel: Int, note: Int, velocity: Int) { handleNote(note, velocity) }
            override fun onNoteOff(channel: Int, note: Int) {}
            override fun onControlChange(channel: Int, controller: Int, value: Int) { handleCc(controller, value) }
        })
        midi = m
        m.start()
    }

    private fun loadMidiMap() {
        midiMap.clear()
        for (e in prefs.midiMap.split(';')) {
            val k = e.substringBefore('=', ""); val v = e.substringAfter('=', "")
            if (k.isNotEmpty() && v.isNotEmpty()) midiMap[k] = v
        }
        midiMappingCount = midiMap.size
    }

    private fun saveMidiMap() { prefs.midiMap = midiMap.entries.joinToString(";") { "${it.key}=${it.value}" }; midiMappingCount = midiMap.size }

    fun startMidiLearn(t: MidiTarget, arg: Int) { midiLearn = t; midiLearnArg = arg }
    fun cancelMidiLearn() { midiLearn = MidiTarget.NONE }
    fun clearMidiMap() { midiMap.clear(); saveMidiMap() }

    private fun handleNote(note: Int, vel: Int) {
        if (midiLearn == MidiTarget.PAD) {
            midiMap.entries.removeAll { it.value == "pad:$midiLearnArg" }
            midiMap["n:$note"] = "pad:$midiLearnArg"
            saveMidiMap(); midiLearn = MidiTarget.NONE
            return
        }
        val t = midiMap["n:$note"]
        val pad = if (t != null && t.startsWith("pad:")) t.substring(4).toIntOrNull()
        else if (t == null && note in 36..51) note - 36 else null
        if (pad != null) triggerPad(pad, vel / 127f)
    }

    private fun handleCc(cc: Int, value: Int) {
        if (midiLearn == MidiTarget.FADER) {
            midiMap.entries.removeAll { it.value == "fader:$midiLearnArg" }
            midiMap["c:$cc"] = "fader:$midiLearnArg"
            saveMidiMap(); midiLearn = MidiTarget.NONE
            return
        }
        val t = midiMap["c:$cc"] ?: return
        if (t.startsWith("fader:")) {
            val id = t.substring(6).toIntOrNull() ?: return
            if (project.scene.channel(id) == null) return
            val db = FaderMap.posToDb(value / 127f)
            changeChannel(id) { it.copy(faderDb = db) }
        }
    }

    // =====================================================================
    // Util
    // =====================================================================
    fun say(resId: Int, arg: String? = null) { message = UiMessage(resId, arg) }

    fun dismissMessage() { message = null }

    override fun onCleared() {
        stopTicking()
        main.removeCallbacks(autosave)
        dirty = true
        saveNow()
        midi?.stop()
        io.shutdown()
        super.onCleared()
    }

    private companion object {
        const val EXT = ".gpmixa"
    }
}
