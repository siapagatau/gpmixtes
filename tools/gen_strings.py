#!/usr/bin/env python3
"""Menghasilkan res/values/strings.xml (en) dan res/values-in/strings.xml (id) dari satu sumber,
lalu memeriksa bahwa setiap R.string.* di kode ada di kedua bahasa dan argumen format-nya sama."""
import re, sys, pathlib
from xml.sax.saxutils import escape

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
SRC = ROOT / "app/src/main/java"

S = {}  # key -> (en, id)
def add(k, en, idn): S[k] = (en, idn)

add("app_name", "GP Mix", "GP Mix")
add("notif_title", "GP Mix is running", "GP Mix sedang berjalan")
add("notif_text", "Tap to open the mixer", "Ketuk untuk membuka mixer")
add("tab_mixer", "Mixer", "Mixer"); add("tab_pads", "Pads & Media", "Pad & Media"); add("tab_settings", "Settings", "Pengaturan")
add("start", "Start", "Mulai"); add("stop", "Stop", "Berhenti")
add("ok", "OK", "OK"); add("cancel", "Cancel", "Batal"); add("close", "Close", "Tutup"); add("delete", "Delete", "Hapus")
add("name", "Name", "Nama"); add("loading", "Loading…", "Memuat…"); add("reset", "Reset", "Reset")
add("undo", "Undo", "Urungkan"); add("redo", "Redo", "Ulangi")
add("top_status", "%1$s · latency ≈ %2$.1f ms · xruns %3$d", "%1$s · latensi ≈ %2$.1f md · xrun %3$d")
add("engine_error_title", "Audio engine could not start", "Mesin audio tidak bisa dijalankan")
add("engine_error_text", "Details: %1$s", "Rincian: %1$s")
# scene
add("scene", "Scene", "Scene"); add("scene_menu", "Scene ▾", "Scene ▾")
add("scene_new", "New scene", "Scene baru"); add("scene_duplicate", "Duplicate scene", "Gandakan scene")
add("scene_rename", "Rename", "Ganti nama"); add("scene_delete", "Delete scene", "Hapus scene")
add("scene_delete_confirm", "Delete scene “%1$s”? You can undo this.", "Hapus scene “%1$s”? Bisa diurungkan.")
add("cd_scene", "Scene %1$s", "Scene %1$s")
# channels
add("add_channel", "Add channel", "Tambah channel")
add("kind_input", "Input", "Input"); add("kind_aux", "Aux", "Aux"); add("kind_group", "Group", "Group"); add("kind_master", "Master", "Master")
add("edition_limits", "%1$s edition: %2$d / %3$d inputs", "Edisi %1$s: %2$d / %3$d input")
add("cd_open_channel", "%1$s, %2$s channel. Open settings", "%1$s, channel %2$s. Buka pengaturan")
add("cd_pan", "Pan %1$s", "Pan %1$s"); add("cd_mute", "Mute %1$s", "Mute %1$s"); add("cd_solo", "Solo %1$s", "Solo %1$s")
add("cd_fader", "Fader %1$s", "Fader %1$s"); add("cd_color", "Colour %1$d", "Warna %1$d")
add("channel_delete", "Delete channel", "Hapus channel")
add("channel_delete_confirm", "Delete “%1$s”? Routing and sends that use it are cleaned up. You can undo this.", "Hapus “%1$s”? Routing dan send yang memakainya ikut dibersihkan. Bisa diurungkan.")
add("input_source", "Input source", "Sumber input")
add("src_hw", "Hardware input %1$d", "Input perangkat %1$d"); add("src_pads", "Sample Pads", "Sample Pad")
add("src_player", "Player", "Player"); add("src_app", "App audio", "Audio aplikasi"); add("src_none", "None", "Tidak ada")
add("stereo", "Stereo", "Stereo"); add("polarity", "Ø Polarity", "Ø Polaritas")
add("safe_desc", "Safe: not affected by Solo", "Safe: tidak terpengaruh Solo")
add("trim", "Trim", "Trim"); add("filters", "Filters", "Filter")
add("hpf", "High-pass", "High-pass"); add("lpf", "Low-pass", "Low-pass")
add("routing", "Routing", "Routing")
add("routing_hint", "Choose where this channel's output goes.", "Pilih tujuan keluaran channel ini.")
add("route_to", "Route to %1$s", "Arahkan ke %1$s")
add("sends", "Sends (to Aux)", "Send (ke Aux)"); add("send_add", "Add send", "Tambah send")
add("send_pre", "Pre-fader send", "Send pre-fader"); add("send_level", "Send level %1$s", "Level send %1$s")
add("inserts", "Inserts (%1$d / %2$d)", "Insert (%1$d / %2$d)"); add("insert_add", "Add effect", "Tambah efek")
add("channel_presets", "Channel presets", "Preset channel")
add("channel_presets_hint", "Replaces this channel's effects and filters with a ready-made starting point.", "Mengganti efek dan filter channel ini dengan titik awal siap pakai.")
# effects
add("bypass", "Bypass", "Bypass"); add("gain_reduction", "Gain reduction: %1$.1f dB", "Gain reduction: %1$.1f dB")
add("preset_save", "Save preset", "Simpan preset"); add("preset_load", "Load preset", "Muat preset")
add("key_source", "Key source", "Sumber Key"); add("key_self", "Own signal", "Sinyal sendiri")
# pads / media
add("pads", "Sample Pads", "Sample Pad"); add("pads_stop", "Stop all", "Hentikan semua")
add("pads_master", "Pads level", "Level pad")
add("pads_hint", "Tap to play, long-press to edit. A hardware keyboard works too: 1234 / QWER / ASDF / ZXCV.", "Ketuk untuk memutar, tekan lama untuk mengubah. Keyboard fisik juga bisa: 1234 / QWER / ASDF / ZXCV.")
add("pad_empty", "Empty", "Kosong"); add("pad_title", "Pad %1$d", "Pad %1$d")
add("cd_pad", "Pad %1$d, %2$s", "Pad %1$d, %2$s"); add("pad_trigger", "Play", "Putar"); add("pad_edit", "Edit pad", "Ubah pad")
add("pad_load", "Load sound", "Muat suara"); add("pad_clear", "Clear", "Kosongkan")
add("pad_gain", "Gain", "Gain"); add("pad_start", "Start", "Mulai"); add("pad_end", "End", "Akhir")
add("pad_choke", "Choke group (pads in the same group cut each other)", "Grup choke (pad satu grup saling memotong)")
add("choke_off", "No choke", "Tanpa choke"); add("choke_group", "Choke group %1$d", "Grup choke %1$d")
add("pad_normalize", "Normalize", "Normalisasi")
add("player", "Player", "Player"); add("player_empty", "No file loaded", "Belum ada file")
add("player_load", "Load file", "Muat file"); add("play", "Play", "Putar"); add("pause", "Pause", "Jeda"); add("loop", "Loop", "Ulang")
add("player_seek", "Position", "Posisi"); add("player_volume", "Player volume", "Volume Player")
add("player_hint", "Route it to a channel by choosing “Player” as that channel's input source.", "Arahkan ke channel dengan memilih “Player” sebagai sumber input channel itu.")
add("recorder", "Recorder", "Perekam"); add("rec_source", "Source", "Sumber")
add("rec_start", "Record", "Rekam"); add("rec_stop", "Stop recording", "Hentikan rekaman")
add("retro_enable", "Retrospective Record (keeps the last 5 min)", "Retrospective Record (menyimpan 5 menit terakhir)")
add("retro_hint", "Off by default. While on, the app keeps the last 5 minutes of the master mix in memory only; nothing is written until you save.", "Mati secara default. Saat aktif, aplikasi menyimpan 5 menit terakhir master mix hanya di memori; tidak ada yang ditulis sebelum Anda menyimpan.")
add("retro_save", "Save last 5 min", "Simpan 5 menit terakhir")
add("recordings", "Recordings", "Rekaman"); add("save_as", "Save as…", "Simpan sebagai…")
# settings
add("audio_setup", "Audio setup", "Pengaturan audio")
add("input_device", "Input", "Input"); add("output_device", "Output", "Output"); add("device_default", "System default", "Bawaan sistem")
add("refresh_devices", "Refresh device list", "Segarkan daftar perangkat")
add("input_channels", "Input channels to use", "Jumlah kanal input"); add("cd_input_channels", "%1$d input channels", "%1$d kanal input")
add("buffer_size", "Buffer size: %1$d × burst", "Ukuran buffer: %1$d × burst")
add("buffer_hint", "Smaller = lower latency but more risk of crackles. Raise it if you hear dropouts.", "Lebih kecil = latensi lebih rendah tetapi lebih berisiko pecah. Naikkan jika terdengar putus-putus.")
add("engine_info", "%1$d Hz · burst %2$d · buffer %3$d frames · latency ≈ %4$.1f ms · xruns %5$d", "%1$d Hz · burst %2$d · buffer %3$d frame · latensi ≈ %4$.1f md · xrun %5$d")
add("engine_stopped", "Engine stopped.", "Mesin berhenti.")
add("latency_hint", "Lowest latency: wired headphones or a USB audio interface. Bluetooth adds noticeable delay.", "Latensi terendah: headphone kabel atau interface audio USB. Bluetooth menambah jeda yang terasa.")
add("mix_settings", "Mix", "Mix"); add("tempo", "Tempo: %1$d BPM", "Tempo: %1$d BPM")
add("ducking_enable", "Enable Ducking", "Aktifkan Ducking"); add("master_mono", "Master mono", "Master mono"); add("pan_law", "Pan law", "Pan law")
add("app_audio", "App audio capture", "Tangkap audio aplikasi")
add("app_audio_hint", "Brings another app's sound (game, music player) into a channel set to “App audio”. Android still plays that sound on the device as well; it cannot be muted from here.", "Membawa suara aplikasi lain (game, pemutar musik) ke channel bersumber “Audio aplikasi”. Android tetap memutar suara itu di perangkat; tidak bisa dibisukan dari sini.")
add("capture_start", "Start capture", "Mulai tangkap"); add("capture_stop", "Stop capture", "Hentikan tangkap")
add("capture_unsupported", "Requires Android 10 or newer.", "Membutuhkan Android 10 atau lebih baru.")
add("midi", "MIDI", "MIDI"); add("midi_none", "No MIDI device connected.", "Tidak ada perangkat MIDI terhubung.")
add("midi_learn", "MIDI learn", "MIDI learn"); add("midi_waiting", "Waiting for MIDI…", "Menunggu MIDI…")
add("midi_learn_fader", "Learn a fader:", "Pelajari fader:"); add("midi_pick_channel", "Pick channel", "Pilih channel")
add("midi_mappings", "%1$d MIDI mappings (notes 36–51 trigger pads by default)", "%1$d pemetaan MIDI (nada 36–51 memicu pad secara default)")
add("midi_clear", "Clear MIDI mappings", "Hapus pemetaan MIDI")
add("project", "Project", "Project"); add("project_current", "Current: %1$s", "Saat ini: %1$s")
add("project_new", "New project", "Project baru"); add("project_open", "Open project", "Buka project")
add("project_none", "No saved projects.", "Belum ada project tersimpan.")
add("project_export", "Export…", "Ekspor…"); add("project_import", "Import…", "Impor…")
add("project_reset", "Restore default mixer", "Pulihkan mixer default")
add("project_reset_confirm", "Replace all scenes with the default “Singing / Public Chat / Private Chat” set? Your pads stay. You can undo this.", "Ganti semua scene dengan set default “Singing / Public Chat / Private Chat”? Pad Anda tetap. Bisa diurungkan.")
add("appearance", "Appearance", "Tampilan"); add("theme_default", "Default", "Default"); add("theme_studio", "Studio Teal", "Studio Teal")
add("language", "Language", "Bahasa"); add("lang_system", "System", "Sistem")
add("info", "Information", "Informasi")
add("shortcuts_text", "Keyboard shortcuts (hardware keyboard): Ctrl+Z undo · Ctrl+Shift+Z or Ctrl+Y redo · Ctrl+S save · pads 1234 / QWER / ASDF / ZXCV on the Pads tab.", "Pintasan keyboard (keyboard fisik): Ctrl+Z urungkan · Ctrl+Shift+Z atau Ctrl+Y ulangi · Ctrl+S simpan · pad 1234 / QWER / ASDF / ZXCV di tab Pad.")
add("about", "About", "Tentang"); add("licenses", "Licences", "Lisensi"); add("android_limits", "Android limits", "Batasan Android")
add("about_text", "GP Mix for Android %1$s (%2$s edition)\nA digital mixer for streamers and musicians: strips, groups, aux sends, 18 effects, sample pads, player and recorder.\n\n© 2025–2026 GP Audio. All rights reserved.", "GP Mix untuk Android %1$s (edisi %2$s)\nMixer digital untuk streamer dan musisi: strip, group, aux send, 18 efek, sample pad, player, dan perekam.\n\n© 2025–2026 GP Audio. Seluruh hak dilindungi.")
add("android_limits_text",
    "GP Mix on Android works inside the app. Android does not allow a few things the desktop version does:\n\n• No VST/AU plug-ins: the 18 built-in effects are used instead.\n• No virtual audio cable or Output Router to other apps. Your mix goes to the selected output (speaker, headphones, USB or Bluetooth).\n• Captured app audio (Android 10+) is still played by that app on the device, and it cannot be muted from here.\n• Other apps cannot receive the mix as their microphone without special hardware or root.\n• Latency depends on the phone. Use wired headphones or a USB audio interface for the best result.\n• Edition limits are also capped by the engine: at most 64 channels, 8 Aux buses and 8 Groups per scene.",
    "GP Mix di Android bekerja di dalam aplikasi. Android tidak mengizinkan beberapa hal yang ada di versi desktop:\n\n• Tidak ada plug-in VST/AU: dipakai 18 efek bawaan.\n• Tidak ada kabel audio virtual atau Output Router ke aplikasi lain. Hasil mix dikirim ke keluaran terpilih (speaker, headphone, USB, atau Bluetooth).\n• Audio aplikasi yang ditangkap (Android 10+) tetap diputar aplikasi itu di perangkat dan tidak bisa dibisukan dari sini.\n• Aplikasi lain tidak bisa menerima mix sebagai mikrofonnya tanpa perangkat khusus atau root.\n• Latensi tergantung ponsel. Pakai headphone kabel atau interface audio USB untuk hasil terbaik.\n• Batas edisi juga dibatasi engine: maksimal 64 channel, 8 bus Aux, dan 8 Group per scene.")
add("donation_text", "GP Mix is made independently. If it helps your streams or music, please consider supporting its development. Thank you!", "GP Mix dibuat secara mandiri. Jika membantu siaran atau musik Anda, mohon pertimbangkan mendukung pengembangannya. Terima kasih!")
add("donation_done", "I already donate", "Saya sudah donasi")
add("donate", "Support GP Mix", "Dukung GP Mix")
# messages
add("msg_limit_reached", "Limit reached for the %1$s edition.", "Batas edisi %1$s tercapai.")
add("msg_mic_denied", "Microphone permission denied. Enable it in Settings > Apps > GP Mix.", "Izin mikrofon ditolak. Aktifkan di Pengaturan > Aplikasi > GP Mix.")
add("msg_output_failed", "Could not open the audio output.", "Tidak bisa membuka keluaran audio.")
add("msg_input_failed", "Could not open the microphone input. The mixer runs without it.", "Tidak bisa membuka input mikrofon. Mixer berjalan tanpa itu.")
add("msg_service_failed", "Background service failed: %1$s", "Layanan latar gagal: %1$s")
add("msg_start_first", "Start the mixer first.", "Mulai mixer terlebih dahulu.")
add("msg_save_failed", "Could not save the file.", "Tidak bisa menyimpan file.")
add("msg_open_failed", "Could not open that project.", "Tidak bisa membuka project itu.")
add("msg_import_failed", "Import failed. %1$s", "Impor gagal. %1$s")
add("msg_imported", "Project imported.", "Project diimpor.")
add("msg_exported", "File saved.", "File tersimpan.")
add("msg_trimmed", "Project imported; %1$s items above this edition's limits were removed.", "Project diimpor; %1$s item di atas batas edisi ini dihapus.")
add("msg_preset_invalid", "That is not a valid effect preset.", "Itu bukan preset efek yang valid.")
add("msg_preset_wrong_type", "That preset is for a different effect.", "Preset itu untuk efek yang berbeda.")
add("msg_pad_missing", "Sound file for pad “%1$s” is missing.", "File suara pad “%1$s” hilang.")
add("msg_pad_too_long", "Sound is too long for a pad (max %1$s seconds).", "Suara terlalu panjang untuk pad (maks %1$s detik).")
add("msg_player_too_long", "File is too long for the Player (about 8 minutes at most).", "File terlalu panjang untuk Player (maksimal sekitar 8 menit).")
add("msg_decode_failed", "Could not read that audio file. %1$s", "Tidak bisa membaca file audio itu. %1$s")
add("msg_rec_error", "Recording problem: %1$s", "Masalah perekaman: %1$s")
add("msg_rec_saved", "Saved %1$s", "Tersimpan %1$s")
add("msg_retro_off", "Turn on Retrospective Record and start the mixer first.", "Aktifkan Retrospective Record dan mulai mixer terlebih dahulu.")
add("msg_retro_empty", "Nothing recorded yet.", "Belum ada yang terekam.")
add("msg_capture_denied", "App audio capture was not allowed.", "Tangkap audio aplikasi tidak diizinkan.")
add("msg_capture_error", "App audio capture stopped: %1$s", "Tangkap audio aplikasi berhenti: %1$s")
add("donate_url", "", "")

NO_TRANSLATE = {"donate_url", "app_name"}

def fmt(v):
    v = escape(v).replace("'", "\\'").replace("\n", "\\n")
    return v

def args(v): return sorted(re.findall(r"%\d\$[sdf.\d]*[sdf]", v))

def write(path, idx):
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for k, vs in S.items():
        if idx == 1 and k in NO_TRANSLATE: continue
        attr = ' translatable="false"' if (k in NO_TRANSLATE and idx == 0) else ""
        lines.append(f'    <string name="{k}"{attr}>{fmt(vs[idx])}</string>')
    lines.append("</resources>\n")
    path.write_text("\n".join(lines), encoding="utf-8")

def main():
    write(RES / "values/strings.xml", 0)
    write(RES / "values-in/strings.xml", 1)
    used = set()
    for f in SRC.rglob("*.kt"):
        used |= set(re.findall(r"R\.string\.([a-z_0-9]+)", f.read_text()))
    missing = sorted(used - set(S)); unused = sorted(set(S) - used)
    bad = [k for k, (e, i) in S.items() if args(e) != args(i)]
    print(f"kunci dipakai: {len(used)}, didefinisikan: {len(S)}")
    if missing: print("KURANG di strings:", missing)
    if unused: print("tidak dipakai (boleh):", unused)
    if bad: print("ARGUMEN FORMAT BEDA en/id:", bad)
    sys.exit(1 if (missing or bad) else 0)

main()
