# GP Mix untuk Android (v2.0)

Mixer digital untuk streamer dan musisi, adaptasi Android dari GP Mix macOS (Lite / Plus / Pro).
Engine audio C++17 + Oboe (latensi rendah), UI Jetpack Compose. Lihat `docs/ANDROID_ADAPTATION.md` untuk pemetaan
manual → Android, batasan, dan roadmap.

## Fitur
Strip Audio/Aux/Group/Master, routing ke Group dan Aux, send pre/post, HPF/LPF, 18 efek (insert, Key side-chain),
scene (Singing / Public Chat / Private Chat), preset channel, 16 Sample Pad, Player, perekam 24-bit,
Retrospective Record, tangkap audio aplikasi (Android 10+), MIDI (pad dan fader), undo/redo, EN/ID, tema.

## Build di GitHub Actions
1. Push seluruh isi folder ini ke branch `main` repo GitHub.
2. Tab **Actions → Build Android APK** berjalan otomatis (tes engine host → build 3 edisi).
3. Unduh APK dari **Artifacts → GPMix-APK**: `apk/lite|plus|pro/debug/*.apk`.
4. APK bertanda tangan (opsional): secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`;
   push tag `v2.0.0` untuk membuat GitHub Release.

## Build lokal
JDK 17, Android SDK 34 + NDK + CMake 3.22.1, Gradle 8.9: `gradle assembleLiteDebug` (atau `assemblePlusDebug`, `assembleProDebug`).

## Tes tanpa Android
    bash native-tests/run.sh          # DSP + mixer C++ di host (tambah `san` untuk ASAN+UBSan)
    ./jvm-tests/run.sh                # model/sinkronisasi Kotlin di JVM (butuh GRADLE_LIB=<gradle>/lib)
    python3 tools/gen_strings.py      # hasilkan strings en/id dan cek semua R.string ada

## Catatan
Pakai headphone kabel atau interface USB: mic + speaker = feedback, Bluetooth menambah jeda.
Ganti `LICENSE` dengan teks lisensi/EULA lengkap Anda sebelum merilis. Isi `donate_url` untuk tombol dukungan.
