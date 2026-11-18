package com.gpaudio.gpmix

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.gpaudio.gpmix.audio.AudioService
import com.gpaudio.gpmix.ui.GpMixApp
import com.gpaudio.gpmix.ui.MediaActions
import com.gpaudio.gpmix.ui.SettingsActions
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: MixerViewModel by viewModels()

    private var pendingPad = -1
    private var pendingPresetJson: String? = null
    private var pendingRecording: File? = null
    private var startAfterPermission = false

    override fun attachBaseContext(newBase: Context) {
        val lang = Prefs(newBase).language
        if (lang == "system") { super.attachBaseContext(newBase); return }
        val cfg = Configuration(newBase.resources.configuration)
        val loc = Locale(if (lang == "id") "in" else lang)
        Locale.setDefault(loc)
        cfg.setLocale(loc)
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }

    // ---- izin ----
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasMic()) { if (startAfterPermission) startMixer() } else vm.say(R.string.msg_mic_denied)
        startAfterPermission = false
    }

    // ---- pemilih file ----
    private val padPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && pendingPad >= 0) vm.loadPadFromUri(pendingPad, uri)
    }
    private val playerPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadPlayerFromUri(uri)
    }
    private val projectExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeText(uri, vm.exportText())
    }
    private val projectImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readText(uri, 8 shl 20)?.let { vm.importText(it) } ?: vm.say(R.string.msg_import_failed, "")
    }
    private val presetSave = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val j = pendingPresetJson
        if (uri != null && j != null) writeText(uri, j)
        pendingPresetJson = null
    }
    private val presetLoad = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readText(uri, 1 shl 20)?.let { vm.loadPreset(it) } ?: vm.say(R.string.msg_preset_invalid)
    }
    private val recordingSave = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/x-wav")) { uri ->
        val f = pendingRecording
        if (uri != null && f != null) copyFile(f, uri)
        pendingRecording = null
    }
    private val captureConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) vm.onCaptureConsent(r.resultCode, r.data) else vm.say(R.string.msg_capture_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val media = MediaActions(
            pickPad = { i -> pendingPad = i; padPicker.launch(arrayOf("audio/*")) },
            pickPlayer = { playerPicker.launch(arrayOf("audio/*")) },
            saveRecording = { f -> pendingRecording = f; recordingSave.launch(f.name) },
        )
        val settings = SettingsActions(
            exportProject = { projectExport.launch(vm.project.name.replace(Regex("[^\\p{L}\\p{N} _.-]"), "_") + ".gpmixa") },
            importProject = { projectImport.launch(arrayOf("*/*")) },
            startCapture = {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                captureConsent.launch(mpm.createScreenCaptureIntent())
            },
            setLanguage = { code -> vm.prefs.language = code; recreate() },
            setTheme = { vm.changeTheme(it) },
            openDonate = {
                val url = getString(R.string.donate_url)
                if (url.startsWith("https://")) try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
            },
        )
        setContent {
            GpMixApp(
                vm = vm,
                onToggleEngine = { toggleMixer() },
                media = media,
                settings = settings,
                onSavePreset = { json -> pendingPresetJson = json; presetSave.launch("preset.gpmixfx") },
                onLoadPreset = { presetLoad.launch(arrayOf("*/*")) },
            )
        }
    }

    override fun onStart() { super.onStart(); vm.startTicking(); vm.refreshDevices() }

    override fun onStop() {
        vm.stopTicking()
        vm.saveNow()
        super.onStop()
    }

    // ---- mixer ----
    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun toggleMixer() {
        if (vm.running) { stopMixer(); return }
        if (hasMic()) startMixer()
        else {
            startAfterPermission = true
            val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
            permLauncher.launch(p.toTypedArray())
        }
    }

    private fun startMixer() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, AudioService::class.java))
        } catch (e: Exception) {
            vm.say(R.string.msg_service_failed, e.message ?: "")
        }
        if (!vm.startEngine()) stopService(Intent(this, AudioService::class.java))
    }

    private fun stopMixer() {
        vm.stopEngine()
        stopService(Intent(this, AudioService::class.java))
    }

    // ---- util file ----
    private fun writeText(uri: Uri, text: String) {
        try { contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } }
        catch (e: Exception) { vm.say(R.string.msg_save_failed) }
    }

    private fun readText(uri: Uri, max: Int): String? = try {
        contentResolver.openInputStream(uri)?.use { s ->
            val b = s.readNBytesCompat(max + 1)
            if (b.size > max) null else String(b, Charsets.UTF_8)
        }
    } catch (e: Exception) { null }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun copyFile(f: File, uri: Uri) {
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { o -> f.inputStream().use { it.copyTo(o) } }
            vm.say(R.string.msg_exported)
        } catch (e: Exception) { vm.say(R.string.msg_save_failed) }
    }
}
