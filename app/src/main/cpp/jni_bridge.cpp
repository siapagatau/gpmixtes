// Jembatan JNI: com.gpaudio.gpmix.audio.NativeEngine <-> Mixer / AudioIO.
#include <jni.h>

#include "audio_io.h"
#include "fx/fx_registry.h"
#include "mixer.h"

namespace {
struct Engine {
    gp::Mixer mixer;
    gp::AudioIO io{mixer};
};
Engine& eng() {
    static Engine* e = new Engine();  // sengaja tidak pernah dihancurkan (hindari race saat proses berakhir)
    return *e;
}

// RAII untuk array float/short input (read-only).
struct FloatIn {
    JNIEnv* env; jfloatArray a; jfloat* p; jsize n;
    FloatIn(JNIEnv* e, jfloatArray arr) : env(e), a(arr), p(nullptr), n(0) {
        if (arr) { n = e->GetArrayLength(arr); p = e->GetFloatArrayElements(arr, nullptr); }
    }
    ~FloatIn() { if (p) env->ReleaseFloatArrayElements(a, p, JNI_ABORT); }
};
}  // namespace

#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_gpaudio_gpmix_audio_NativeEngine_##name

// ---- siklus hidup ----
JNI(jint, start)(JNIEnv*, jobject, jint inDev, jint outDev, jint inCh, jint bufMult) {
    return eng().io.start(inDev, outDev, inCh, bufMult);
}
JNI(void, stop)(JNIEnv*, jobject) { eng().io.stop(); }
JNI(jfloatArray, info)(JNIEnv* env, jobject) {
    float v[7];
    eng().io.info(v);
    jfloatArray a = env->NewFloatArray(7);
    env->SetFloatArrayRegion(a, 0, 7, v);
    return a;
}
JNI(jstring, describeEffects)(JNIEnv* env, jobject) { return env->NewStringUTF(gp::describeEffects().c_str()); }

// ---- struktur mixer ----
JNI(jboolean, addChannel)(JNIEnv*, jobject, jint id, jint type) { return eng().mixer.addChannel(id, type); }
JNI(jboolean, removeChannel)(JNIEnv*, jobject, jint id) { return eng().mixer.removeChannel(id); }
JNI(void, setChannelParam)(JNIEnv*, jobject, jint id, jint p, jfloat v) { eng().mixer.setChannelParam(id, p, v); }
JNI(void, setInput)(JNIEnv*, jobject, jint id, jint kind, jint idx, jint extra) { eng().mixer.setInput(id, kind, idx, extra); }
JNI(void, setSend)(JNIEnv*, jobject, jint id, jint slot, jint dest, jfloat db, jboolean mute, jboolean pre) {
    eng().mixer.setSend(id, slot, dest, db, mute, pre);
}
JNI(jboolean, setInsert)(JNIEnv*, jobject, jint id, jint slot, jint type) { return eng().mixer.setInsert(id, slot, type); }
JNI(void, setFxParam)(JNIEnv*, jobject, jint id, jint slot, jint idx, jfloat v) { eng().mixer.setFxParam(id, slot, idx, v); }
JNI(void, setInsertFlags)(JNIEnv*, jobject, jint id, jint slot, jboolean bypass, jint key) {
    eng().mixer.setInsertFlags(id, slot, bypass, key);
}
JNI(jfloat, fxGainReduction)(JNIEnv*, jobject, jint id, jint slot) { return eng().mixer.fxGainReduction(id, slot); }
JNI(void, setTempo)(JNIEnv*, jobject, jfloat bpm) { eng().mixer.setTempo(bpm); }
JNI(void, setDuckingEnabled)(JNIEnv*, jobject, jboolean on) { eng().mixer.setDuckingEnabled(on); }
JNI(void, setPanLawDb)(JNIEnv*, jobject, jfloat db) { eng().mixer.setPanLawDb(db); }
JNI(void, setMasterMono)(JNIEnv*, jobject, jboolean on) { eng().mixer.setMasterMono(on); }

// ---- meter ----
JNI(void, readMeters)(JNIEnv* env, jobject, jfloatArray out) {
    if (!out || env->GetArrayLength(out) < 3 * gp::kMaxChannels) return;
    float tmp[3 * gp::kMaxChannels];
    eng().mixer.readMeters(tmp);
    env->SetFloatArrayRegion(out, 0, 3 * gp::kMaxChannels, tmp);
}
JNI(jfloat, padsPeak)(JNIEnv*, jobject) { return eng().mixer.padsPeak(); }

// ---- pads ----
JNI(void, loadPad)(JNIEnv* env, jobject, jint idx, jfloatArray data, jint channels, jint rate) {
    FloatIn in(env, data);
    if (!in.p || channels < 1) return;
    eng().mixer.loadPad(idx, in.p, in.n / channels, channels, rate);
}
JNI(void, clearPad)(JNIEnv*, jobject, jint idx) { eng().mixer.clearPad(idx); }
JNI(void, setPadParam)(JNIEnv*, jobject, jint idx, jint p, jfloat v) { eng().mixer.setPadParam(idx, p, v); }
JNI(void, setPadsMasterDb)(JNIEnv*, jobject, jfloat db) { eng().mixer.setPadsMasterDb(db); }
JNI(void, triggerPad)(JNIEnv*, jobject, jint idx, jfloat vel) { eng().mixer.triggerPad(idx, vel); }
JNI(void, stopPads)(JNIEnv*, jobject) { eng().mixer.stopPads(); }
JNI(jint, padPlayingMask)(JNIEnv*, jobject) { return (jint)eng().mixer.padPlayingMask(); }

// ---- player ----
JNI(void, loadPlayer)(JNIEnv* env, jobject, jshortArray data, jint frames, jint rate) {
    if (!data || frames <= 0 || env->GetArrayLength(data) < frames * 2) return;
    jshort* p = env->GetShortArrayElements(data, nullptr);
    eng().mixer.loadPlayer(reinterpret_cast<const int16_t*>(p), frames, rate);
    env->ReleaseShortArrayElements(data, p, JNI_ABORT);
}
JNI(void, clearPlayer)(JNIEnv*, jobject) { eng().mixer.clearPlayer(); }
JNI(void, playerPlay)(JNIEnv*, jobject, jboolean on) { eng().mixer.playerPlay(on); }
JNI(void, playerLoop)(JNIEnv*, jobject, jboolean on) { eng().mixer.playerLoop(on); }
JNI(void, playerSeek)(JNIEnv*, jobject, jlong frame) { eng().mixer.playerSeek(frame); }
JNI(void, playerVolumeDb)(JNIEnv*, jobject, jfloat db) { eng().mixer.playerVolumeDb(db); }
JNI(jlong, playerPos)(JNIEnv*, jobject) { return (jlong)eng().mixer.playerPos(); }
JNI(jboolean, playerPlaying)(JNIEnv*, jobject) { return eng().mixer.playerPlaying(); }

// ---- masukan aplikasi ----
JNI(jint, pushAppAudio)(JNIEnv* env, jobject, jfloatArray data, jint frames) {
    FloatIn in(env, data);
    if (!in.p || frames <= 0 || in.n < frames * 2) return 0;
    return eng().mixer.pushAppAudio(in.p, frames);
}

// ---- Retrospective Record ----
JNI(jboolean, retroEnable)(JNIEnv*, jobject, jboolean on) { return eng().mixer.retroEnable(on); }
JNI(jboolean, retroEnabled)(JNIEnv*, jobject) { return eng().mixer.retroEnabled(); }
JNI(jint, retroBegin)(JNIEnv*, jobject) { return eng().mixer.retroBegin(); }
JNI(jint, retroRead)(JNIEnv* env, jobject, jint offset, jshortArray dst, jint frames) {
    if (!dst || frames <= 0 || env->GetArrayLength(dst) < frames * 2) return 0;
    jshort* p = env->GetShortArrayElements(dst, nullptr);
    const int n = eng().mixer.retroRead(offset, reinterpret_cast<int16_t*>(p), frames);
    env->ReleaseShortArrayElements(dst, p, 0);
    return n;
}
JNI(jint, retroRate)(JNIEnv*, jobject) { return eng().mixer.retroRate(); }

// ---- rekam Player ----
JNI(jboolean, recStart)(JNIEnv*, jobject, jint src) { return eng().mixer.recStart(src); }
JNI(void, recStop)(JNIEnv*, jobject) { eng().mixer.recStop(); }
JNI(jint, recRead)(JNIEnv* env, jobject, jfloatArray dst, jint maxFrames) {
    if (!dst || maxFrames <= 0 || env->GetArrayLength(dst) < maxFrames * 2) return 0;
    jfloat* p = env->GetFloatArrayElements(dst, nullptr);
    const int n = eng().mixer.recRead(p, maxFrames);
    env->ReleaseFloatArrayElements(dst, p, 0);
    return n;
}
JNI(jint, recOverruns)(JNIEnv*, jobject) { return eng().mixer.recOverruns(); }
