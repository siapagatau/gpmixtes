# GP Mix untuk Android — adaptasi dari manual macOS v2.0

Dokumen ini mencatat bagaimana desain di manual macOS (Lite/Plus/Pro, v2.0 Nov 2026) diterjemahkan ke Android,
apa yang dipertahankan, apa yang diubah, dan apa yang belum ada. Identitas aplikasi tetap: **mixer digital untuk
streamer dan musisi** dengan strip channel, group, aux send, efek berantai, sample pad, player, dan perekam.

## 1. Yang dipertahankan (sesuai manual)
| Konsep manual | Di Android |
|---|---|
| 3 edisi Lite / Plus / Pro | 3 product flavor (`lite`, `plus`, `pro`), applicationId berakhiran `.lite/.plus/.pro` sehingga bisa dipasang bersamaan. Batas input audio 8/32/256, Aux 4/8/128, Group 2/8/32, insert 4/8/16, send 4/8/16. |
| Channel Audio / Aux / Group / Master | Sama. Urutan strip: trim/polaritas → HPF/LPF (12–48 dB/oct) → insert → mute → send pre → fader → send post → pan/balance → meter. |
| Routing | Output mask: Master, Group 1–8, Aux/Bus 1–8. Aux membaca bus pada blok yang sama; channel audio yang membaca bus memakai blok sebelumnya (pencegahan loop). |
| Solo in place + Safe | Solo hanya memengaruhi channel Audio; channel bertanda SAFE tidak ikut dibisukan. |
| Pan law −3…−6 dB, Master mono | Pengaturan > Mix. |
| 18 efek | EQ 6-band + HP/LP, Gate, Expander, Compressor, De-esser, Limiter, Ducking, Chorus, Delay (sinkron tempo), Reverb, Saturator, Tremolo, Auto Pan, Utility, Transposer, Hum Remover (50/60 Hz otomatis), Bitcrusher, Test Tone. Parameter didefinisikan satu kali di C++ dan UI dibuat otomatis darinya. |
| Key Source (side-chain) | Compressor/Gate/Expander/De-esser/Ducking dapat memakai sinyal channel lain sebagai Key (pre-fader, post-mute). |
| Limiter pengaman Master | Selalu aktif, −0,3 dBFS, lookahead 1 ms; tidak bisa dimatikan. |
| Scene | Singing / Public Chat / Private Chat pada project default **My Preset**; scene bisa ditambah, digandakan, diganti nama, dihapus. |
| Preset channel pabrik | Vocal, Music, Guest, TTS, Chat, Game. |
| Preset efek | Disimpan/dimuat sebagai file JSON `.gpmixfx`. |
| Sample Pad 4×4 | 16 pad, gain, start/end, choke group, normalisasi, MIDI learn, tombol keyboard 1234/QWER/ASDF/ZXCV. |
| Player + rekam | Player dengan loop/seek/volume; perekam WAV 24-bit dari Master atau satu channel. |
| Retrospective Record | 5 menit terakhir, **mati secara default**, hanya di memori sampai disimpan. |
| Undo/redo 100 langkah | Ya (snapshot project). |
| Project | `.gpmixa` (JSON, bisa diekspor/diimpor); autosave. |
| Bahasa EN/ID, tema, aksesibilitas | Bahasa per-aplikasi, tema Default dan Studio Teal, label TalkBack untuk fader/pad/tombol. |
| Splash donasi | Dialog donasi saat peluncuran pertama; tombol **“Saya sudah donasi”**. Isi `donate_url` di `tools/gen_strings.py` (kosong = tombol Dukung disembunyikan). |
| Lisensi pihak ketiga | Settings > Informasi > Lisensi (Oboe, AndroidX/Compose, Kotlin; VST3/JUCE tidak dipakai di Android). |

## 2. Yang diubah untuk Android
- **Cmd → Ctrl** untuk pintasan keyboard fisik (Ctrl+Z, Ctrl+Shift+Z/Ctrl+Y, Ctrl+S).
- **Audio I/O**: Oboe (AAudio/OpenSL ES), satu output stereo + input 1–8 kanal bila perangkat mendukung (antarmuka USB). Latensi bergantung ponsel.
- **Audio aplikasi lain** (pengganti jalur virtual cable): AudioPlaybackCapture (Android 10+, izin MediaProjection) menjadi sumber “Audio aplikasi”.
- **Foreground service** bertipe `microphone` (+ `mediaProjection` saat menangkap) agar mixer tetap hidup di latar.
- **Test Tone** default −30 dB (bukan −18) demi keselamatan telinga/speaker ponsel.
- **Batas engine**: maksimal 64 channel, 8 bus Aux, 8 Group per scene. Edisi Pro (Aux 128, Group 32) dibatasi ini; angka manual tetap tercatat di model.
- **Penyimpanan**: file project/pad/rekaman di penyimpanan internal aplikasi; ekspor lewat Storage Access Framework.
- **Panjang file**: pad maks 60 detik; Player maks ±8 menit (batas memori ponsel).

## 3. Batasan Android (jujur)
- Tidak ada VST/AU, virtual cable, atau Output Router ke aplikasi lain.
- Audio aplikasi yang ditangkap tetap terdengar dari aplikasi aslinya (tidak ada “Mute applications”).
- Aplikasi lain tidak dapat menerima hasil mix sebagai mikrofonnya tanpa perangkat khusus/root.
- Harmonizer tidak dibuat (tidak ada di scene default Android).

## 4. Roadmap
Dynamic EQ, Stereo Image, Voice Denoise, Harmonizer, Web Remote, Output Router (bila Android membukanya), output multikanal USB,
daftar kontrol MIDI yang lebih lengkap, penanda gain-reduction di strip, dukungan tablet/landscape yang dioptimalkan.

## 5. Verifikasi yang sudah dilakukan
- Engine C++ (DSP + mixer): 846 pemeriksaan numerik di host, bersih di ASAN, UBSan, dan TSAN (termasuk fuzz perubahan struktur saat render).
- Model Kotlin murni, sinkronisasi ke engine (dengan engine palsu), WAV 24-bit, parser MIDI, skala parameter: 1423 pemeriksaan di JVM (`jvm-tests/run.sh`).
- JNI: nama dan tipe cocok 1:1 dengan `NativeEngine.kt`; sintaks C++ Oboe/JNI diperiksa dengan stub.
- **Belum teruji**: kompilasi Compose/Android dan jalannya di perangkat. Build GitHub Actions pertama adalah uji kompilasi yang sebenarnya.
