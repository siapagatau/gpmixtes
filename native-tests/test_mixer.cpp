// Tes mixer GP Mix di host (tanpa Android).
#include <atomic>
#include <cmath>
#include <cstdio>
#include <thread>
#include <vector>

#include "fx/fx_misc.h"
#include "fx/fx_time.h"
#include "mixer.h"

using namespace gp;

static int g_fail = 0, g_pass = 0;
#define CHECK(cond, ...)                                                                                  \
    do {                                                                                                  \
        if (cond) { ++g_pass; }                                                                           \
        else { ++g_fail; std::printf("  FAIL %s:%d  %s  -> ", __FILE__, __LINE__, #cond); std::printf(__VA_ARGS__); std::printf("\n"); } \
    } while (0)

static const float SR = 48000.f;

struct Rig {
    Mixer m;
    Rig() { m.setSampleRate(SR); }
    // Render 'frames' dengan sinus mono di hw[0] (amplitudo amp, frekuensi hz). Kembalikan interleaved stereo.
    std::vector<float> run(int frames, float amp, float hz = 1000.f, int hwCh = 1, float* phase = nullptr) {
        std::vector<float> hw((size_t)frames * hwCh, 0.f), out((size_t)frames * 2);
        static float ph = 0.f;
        float& p = phase ? *phase : ph;
        for (int i = 0; i < frames; ++i) {
            const float s = amp * std::sin(kTwoPi * p);
            p += hz / SR; p -= std::floor(p);
            for (int c = 0; c < hwCh; ++c) hw[(size_t)i * hwCh + c] = c == 0 ? s : 0.f;
        }
        // potong menjadi callback 192 frame seperti Oboe
        for (int i = 0; i < frames; i += 192) {
            const int n = std::min(192, frames - i);
            m.render(hw.data() + (size_t)i * hwCh, hwCh, out.data() + (size_t)i * 2, n);
        }
        return out;
    }
};
static float peakCh(const std::vector<float>& o, int ch, int from, int to) {
    float p = 0; for (int i = from; i < to; ++i) p = std::max(p, std::fabs(o[(size_t)i * 2 + ch])); return p;
}
static float rmsCh(const std::vector<float>& o, int ch, int from, int to) {
    double s = 0; for (int i = from; i < to; ++i) s += (double)o[(size_t)i * 2 + ch] * o[(size_t)i * 2 + ch];
    return (float)std::sqrt(s / (to - from));
}

static void basic(Rig& r, int audioId = 0) {
    r.m.addChannel(63, CH_MASTER);
    r.m.addChannel(audioId, CH_AUDIO);
    r.m.setInput(audioId, IN_HW, 0, 0);
}

static void testFaderPan() {
    std::printf("[mix] fader, pan, mute\n");
    {
        Rig r; basic(r);
        auto o = r.run(9600, 0.5f);
        // mono center, -3 dB pan law: 0.5 * 0.7071 per sisi
        CHECK(std::fabs(peakCh(o, 0, 4800, 9600) - 0.5f * 0.70711f) < 0.01f, "L %f", peakCh(o, 0, 4800, 9600));
        CHECK(std::fabs(peakCh(o, 1, 4800, 9600) - 0.5f * 0.70711f) < 0.01f, "R");
    }
    {
        Rig r; basic(r);
        r.m.setChannelParam(0, CP_PAN, -1.f);
        auto o = r.run(9600, 0.5f);
        CHECK(std::fabs(peakCh(o, 0, 4800, 9600) - 0.5f) < 0.01f, "hard left L");
        CHECK(peakCh(o, 1, 4800, 9600) < 1e-4f, "hard left R");
    }
    {
        Rig r; basic(r);
        r.m.setChannelParam(0, CP_STEREO, 1.f);       // balance, bukan pan
        r.m.setChannelParam(0, CP_FADER_DB, -6.f);
        auto o = r.run(9600, 0.5f);
        CHECK(std::fabs(peakCh(o, 0, 4800, 9600) - 0.5f * dbToLin(-6.f)) < 0.005f, "fader -6 dB: %f", peakCh(o, 0, 4800, 9600));
    }
    {
        Rig r; basic(r);
        r.m.setChannelParam(0, CP_PAN, 1.f); r.m.setPanLawDb(-6.f);
        auto o = r.run(9600, 0.5f);
        CHECK(std::fabs(peakCh(o, 0, 4800, 9600)) < 1e-3f, "pan law -6, hard right: L=0");
        Rig r2; basic(r2); r2.m.setPanLawDb(-6.f);
        auto o2 = r2.run(9600, 0.5f);
        CHECK(std::fabs(peakCh(o2, 0, 4800, 9600) - 0.25f) < 0.01f, "center -6 dB: %f", peakCh(o2, 0, 4800, 9600));
    }
    {
        Rig r; basic(r);
        r.run(4800, 0.5f);
        r.m.setChannelParam(0, CP_MUTE, 1.f);
        auto o = r.run(9600, 0.5f);
        CHECK(peakCh(o, 0, 960, 9600) < 1e-5f, "mute senyap");
        // tidak ada lonjakan: perubahan antar sampel kecil
        float maxStep = 0; for (int i = 1; i < 960; ++i) maxStep = std::max(maxStep, std::fabs(o[i * 2] - o[(i - 1) * 2]));
        CHECK(maxStep < 0.1f, "mute halus %f", maxStep);
    }
    {
        Rig r; basic(r);
        r.m.setChannelParam(0, CP_TRIM_DB, 6.f); r.m.setChannelParam(0, CP_STEREO, 1.f); r.m.setChannelParam(0, CP_POLARITY, 1.f);
        auto o = r.run(9600, 0.2f);
        // polaritas terbalik + trim 6 dB
        float x = 0.f; auto o2 = r.run(1, 0.2f, 1000.f, 1, &x);
        (void)o2;
        CHECK(std::fabs(peakCh(o, 0, 4800, 9600) - 0.2f * dbToLin(6.f)) < 0.01f, "trim");
    }
}

static void testMasterSafety() {
    std::printf("[mix] limiter pengaman master\n");
    Rig r; basic(r);
    r.m.setChannelParam(0, CP_STEREO, 1.f);
    r.m.setChannelParam(0, CP_TRIM_DB, 24.f);
    r.m.setChannelParam(63, CP_FADER_DB, 6.f);
    auto o = r.run(48000, 0.9f, 300.f);
    const float ceil = dbToLin(-0.3f);
    CHECK(peakCh(o, 0, 0, 48000) <= ceil + 1e-5f, "puncak L %f", peakCh(o, 0, 0, 48000));
    CHECK(peakCh(o, 1, 0, 48000) <= ceil + 1e-5f, "puncak R");
    float ml[2 * kMaxChannels + kMaxChannels];
    r.m.readMeters(ml);
    CHECK(ml[2 * 63] <= ceil + 1e-5f, "meter master pasca-limiter");
    CHECK(ml[2 * kMaxChannels + 63] > 0.5f, "latency master terlapor %f ms", ml[2 * kMaxChannels + 63]);
}

static void testSolo() {
    std::printf("[mix] solo & safe\n");
    Rig r;
    r.m.addChannel(63, CH_MASTER);
    for (int i = 0; i < 3; ++i) { r.m.addChannel(i, CH_AUDIO); r.m.setInput(i, IN_HW, 0, 0); r.m.setChannelParam(i, CP_STEREO, 1.f); }
    auto base = r.run(9600, 0.1f);
    const float one = peakCh(base, 0, 4800, 9600);  // 3 channel bersuara
    CHECK(std::fabs(one - 0.3f) < 0.01f, "tiga channel: %f", one);
    r.m.setChannelParam(1, CP_SOLO, 1.f);
    auto s = r.run(9600, 0.1f);
    CHECK(std::fabs(peakCh(s, 0, 4800, 9600) - 0.1f) < 0.01f, "solo -> hanya satu: %f", peakCh(s, 0, 4800, 9600));
    r.m.setChannelParam(2, CP_SAFE, 1.f);
    auto s2 = r.run(9600, 0.1f);
    CHECK(std::fabs(peakCh(s2, 0, 4800, 9600) - 0.2f) < 0.01f, "solo + safe -> dua: %f", peakCh(s2, 0, 4800, 9600));
}

static void testRouting() {
    std::printf("[mix] group, bus, aux, send\n");
    {   // output ke Group (bukan Master) lalu Group ke Master
        Rig r;
        r.m.addChannel(63, CH_MASTER);
        r.m.addChannel(0, CH_AUDIO); r.m.setInput(0, IN_HW, 0, 0); r.m.setChannelParam(0, CP_STEREO, 1.f);
        r.m.addChannel(10, CH_GROUP); r.m.setChannelParam(10, CP_GROUP_INDEX, 2);
        r.m.setChannelParam(0, CP_OUT_MASK, (float)outGroup(2));
        r.m.setChannelParam(10, CP_FADER_DB, -6.f);
        auto o = r.run(9600, 0.4f);
        CHECK(std::fabs(peakCh(o, 0, 4800, 9600) - 0.4f * dbToLin(-6.f)) < 0.01f, "via group -6: %f", peakCh(o, 0, 4800, 9600));
        r.m.setChannelParam(10, CP_MUTE, 1.f);
        auto o2 = r.run(9600, 0.4f);
        CHECK(peakCh(o2, 0, 2400, 9600) < 1e-5f, "mute group membisukan anggota");
    }
    {   // send ke aux; channel asli diam; aux memberi sinyal
        Rig r;
        r.m.addChannel(63, CH_MASTER);
        r.m.addChannel(0, CH_AUDIO); r.m.setInput(0, IN_HW, 0, 0); r.m.setChannelParam(0, CP_STEREO, 1.f);
        r.m.setChannelParam(0, CP_OUT_MASK, 0.f);  // None
        r.m.addChannel(20, CH_AUX); r.m.setInput(20, IN_BUS, 1, 0);
        r.m.setSend(0, 0, 1, 0.f, false, false);
        auto o = r.run(9600, 0.3f);
        CHECK(std::fabs(peakCh(o, 0, 4800, 9600) - 0.3f) < 0.01f, "send ke aux: %f", peakCh(o, 0, 4800, 9600));
        r.m.setSend(0, 0, 1, -60.f, false, false);
        auto o2 = r.run(9600, 0.3f);
        CHECK(peakCh(o2, 0, 4800, 9600) < 1e-4f, "send -inf");
        r.m.setSend(0, 0, 1, 0.f, true, false);
        auto o3 = r.run(9600, 0.3f);
        CHECK(peakCh(o3, 0, 4800, 9600) < 1e-4f, "send dimute");
    }
    {   // pre vs post fader
        for (int pre = 0; pre < 2; ++pre) {
            Rig r;
            r.m.addChannel(63, CH_MASTER);
            r.m.addChannel(0, CH_AUDIO); r.m.setInput(0, IN_HW, 0, 0); r.m.setChannelParam(0, CP_STEREO, 1.f);
            r.m.setChannelParam(0, CP_OUT_MASK, 0.f);
            r.m.addChannel(20, CH_AUX); r.m.setInput(20, IN_BUS, 0, 0);
            r.m.setSend(0, 0, 0, 0.f, false, pre == 1);
            r.m.setChannelParam(0, CP_FADER_DB, -60.f);
            auto o = r.run(9600, 0.3f);
            const float p = peakCh(o, 0, 4800, 9600);
            if (pre) CHECK(std::fabs(p - 0.3f) < 0.01f, "pre-fader tidak bergantung fader: %f", p);
            else CHECK(p < 1e-4f, "post-fader mengikuti fader: %f", p);
        }
    }
    {   // aux dengan insert Reverb: ada ekor setelah sumber berhenti
        Rig r;
        r.m.addChannel(63, CH_MASTER);
        r.m.addChannel(0, CH_AUDIO); r.m.setInput(0, IN_HW, 0, 0); r.m.setChannelParam(0, CP_STEREO, 1.f);
        r.m.setChannelParam(0, CP_OUT_MASK, 0.f);
        r.m.addChannel(20, CH_AUX); r.m.setInput(20, IN_BUS, 0, 0);
        CHECK(r.m.setInsert(20, 0, FX_REVERB), "setInsert");
        r.m.setFxParam(20, 0, Reverb::MIX, 100.f);
        r.m.setSend(0, 0, 0, 0.f, false, false);
        r.run(9600, 0.5f, 440.f);
        auto tail = r.run(9600, 0.f);
        CHECK(rmsCh(tail, 0, 0, 9600) > 0.001f, "ekor reverb terdengar %f", rmsCh(tail, 0, 0, 9600));
    }
    {   // loop bus dicegah: input BUS + output ke bus yang sama tidak meledak
        Rig r;
        r.m.addChannel(63, CH_MASTER);
        r.m.addChannel(0, CH_AUDIO); r.m.setInput(0, IN_BUS, 3, 0);
        r.m.setChannelParam(0, CP_OUT_MASK, (float)(outBus(3) | 1u));
        r.m.setSend(0, 0, 3, 6.f, false, false);
        r.m.addChannel(1, CH_AUDIO); r.m.setInput(1, IN_HW, 0, 0); r.m.setChannelParam(1, CP_OUT_MASK, (float)outBus(3));
        auto o = r.run(48000, 0.5f);
        CHECK(peakCh(o, 0, 0, 48000) < 1.f, "tanpa feedback: %f", peakCh(o, 0, 0, 48000));
    }
}

static void testSidechain() {
    std::printf("[mix] ducking dengan Key Source antar channel\n");
    Rig r;
    r.m.addChannel(63, CH_MASTER);
    // ch0 = suara (hw 0), ch1 = musik (hw 1)
    r.m.addChannel(0, CH_AUDIO); r.m.setInput(0, IN_HW, 0, 0); r.m.setChannelParam(0, CP_STEREO, 1.f);
    r.m.addChannel(1, CH_AUDIO); r.m.setInput(1, IN_HW, 1, 0); r.m.setChannelParam(1, CP_STEREO, 1.f);
    r.m.setInsert(1, 0, FX_DUCKING);
    r.m.setFxParam(1, 0, Ducking::ATT, 5.f); r.m.setFxParam(1, 0, Ducking::REL, 100.f);
    r.m.setInsertFlags(1, 0, false, 0);  // key = channel 0
    r.m.setChannelParam(0, CP_FADER_DB, -60.f);  // suara tidak terdengar; key tidak bergantung fader
    std::vector<float> hw(2 * 48000), out(2 * 48000);
    for (int i = 0; i < 48000; ++i) {
        hw[2 * i] = 0.5f * std::sin(kTwoPi * 200.f * i / SR);          // suara
        hw[2 * i + 1] = 0.2f * std::sin(kTwoPi * 440.f * i / SR);      // musik
    }
    for (int i = 0; i < 48000; i += 192) r.m.render(hw.data() + 2 * i, 2, out.data() + 2 * i, std::min(192, 48000 - i));
    const float duckedRms = rmsCh(out, 0, 36000, 48000);
    const float expect = 0.2f * 0.70711f * dbToLin(-12.f);
    CHECK(std::fabs(duckedRms - expect) / expect < 0.15f, "musik ter-duck: %f (harap %f)", duckedRms, expect);
    // insert dibypass -> tidak ter-duck
    r.m.setInsertFlags(1, 0, true, 0);
    for (int i = 0; i < 48000; i += 192) r.m.render(hw.data() + 2 * i, 2, out.data() + 2 * i, std::min(192, 48000 - i));
    CHECK(std::fabs(rmsCh(out, 0, 36000, 48000) - 0.2f * 0.70711f) < 0.01f, "bypass menghentikan ducking");
    // mute pada channel key mematikan ducking (mic mati = tidak sedang bicara)
    r.m.setInsertFlags(1, 0, false, 0);
    r.m.setChannelParam(0, CP_MUTE, 1.f);
    for (int i = 0; i < 48000; i += 192) r.m.render(hw.data() + 2 * i, 2, out.data() + 2 * i, std::min(192, 48000 - i));
    CHECK(std::fabs(rmsCh(out, 0, 36000, 48000) - 0.2f * 0.70711f) < 0.01f, "key dimute -> tidak ducking");
    r.m.setChannelParam(0, CP_MUTE, 0.f);
    // hapus channel key -> keySource dibersihkan, tidak crash
    r.m.setInsertFlags(1, 0, false, 0);
    r.m.removeChannel(0);
    for (int i = 0; i < 48000; i += 192) r.m.render(hw.data() + 2 * i, 2, out.data() + 2 * i, std::min(192, 48000 - i));
    CHECK(std::fabs(rmsCh(out, 0, 36000, 48000) - 0.2f * 0.70711f) < 0.01f, "key hilang -> tidak ducking");
}

static void testInsertLatencyBypass() {
    std::printf("[mix] latency insert & bypass\n");
    Rig r; basic(r);
    r.m.setChannelParam(0, CP_STEREO, 1.f);
    r.m.setInsert(0, 0, FX_LIMITER);  // lookahead 2 ms = 96
    r.run(1920, 0.1f);
    float ml[3 * kMaxChannels]; r.m.readMeters(ml);
    CHECK(std::fabs(ml[2 * kMaxChannels + 0] - 2.0f) < 0.05f, "latency channel %f ms", ml[2 * kMaxChannels]);
    r.m.setInsertFlags(0, 0, true, -1);
    r.run(1920, 0.1f); r.m.readMeters(ml);
    CHECK(ml[2 * kMaxChannels] == 0.f, "bypass -> latency 0: %f", ml[2 * kMaxChannels]);
    CHECK(!r.m.setInsert(0, 0, 999), "tipe tidak valid ditolak");
    CHECK(r.m.setInsert(0, 0, -1), "kosongkan slot");
}

static void testPads() {
    std::printf("[pads] trigger, choke, trim\n");
    Rig r; r.m.addChannel(63, CH_MASTER);
    r.m.addChannel(5, CH_AUDIO); r.m.setInput(5, IN_PADS, 0, 0);
    r.m.setChannelParam(5, CP_PAN, 0.f);
    std::vector<float> s(48000, 0.5f);  // 1 detik DC 0.5 mono
    r.m.loadPad(0, s.data(), 48000, 1, 48000);
    r.m.loadPad(1, s.data(), 48000, 1, 48000);
    auto o = r.run(4800, 0.f);
    CHECK(peakCh(o, 0, 0, 4800) < 1e-6f, "diam sebelum trigger");
    r.m.triggerPad(0, 1.f);
    auto p = r.run(9600, 0.f);
    CHECK(std::fabs(peakCh(p, 0, 4800, 9600) - 0.5f) < 0.01f, "pad terdengar: %f", peakCh(p, 0, 4800, 9600));
    CHECK((r.m.padPlayingMask() & 1u) == 1u, "mask playing");
    // choke: pad 1 memotong pad 0
    r.m.setPadParam(0, PP_CHOKE, 1.f); r.m.setPadParam(1, PP_CHOKE, 1.f);
    r.m.triggerPad(1, 1.f);
    r.run(960, 0.f);
    CHECK((r.m.padPlayingMask() & 1u) == 0u && (r.m.padPlayingMask() & 2u) == 2u, "choke mask %x", r.m.padPlayingMask());
    // one-shot selesai sendiri
    r.m.stopPads();
    r.run(1920, 0.f);
    CHECK(r.m.padPlayingMask() == 0u, "stop");
    // trim: end = 0.1 -> 0.1 detik lalu berhenti
    r.m.setPadParam(0, PP_END, 0.1f);
    r.m.triggerPad(0, 1.f);
    r.run(2400, 0.f);
    CHECK((r.m.padPlayingMask() & 1u) == 1u, "masih jalan 50ms");
    r.run(4800, 0.f);
    CHECK((r.m.padPlayingMask() & 1u) == 0u, "berhenti di trim end");
    // normalize
    std::vector<float> q(48000, 0.1f);
    r.m.loadPad(2, q.data(), 48000, 1, 48000);
    r.m.setPadParam(2, PP_NORMALIZE, 1.f);
    r.m.triggerPad(2, 1.f);
    auto n = r.run(9600, 0.f);
    CHECK(std::fabs(peakCh(n, 0, 4800, 9600) - 0.8912509f) < 0.01f, "normalize -1 dBFS: %f", peakCh(n, 0, 4800, 9600));
    // sample rate berbeda: 24 kHz diputar 2x lebih cepat -> selesai dalam 0.5 s
    std::vector<float> h(12000, 0.5f);
    r.m.stopPads(); r.run(960, 0.f);
    r.m.loadPad(3, h.data(), 12000, 1, 24000);  // 12000 frame @24k = 0,5 detik
    r.m.triggerPad(3, 1.f);
    r.run(14400, 0.f);
    CHECK((r.m.padPlayingMask() & 8u) == 8u, "pad 24k masih jalan di 0.3 s");
    r.run(14400, 0.f);
    CHECK((r.m.padPlayingMask() & 8u) == 0u, "pad 24k selesai ~0.5 s");
    r.m.clearPad(3);
    r.m.triggerPad(3, 1.f);
    r.run(960, 0.f);
    CHECK((r.m.padPlayingMask() & 8u) == 0u, "pad kosong tidak berbunyi");
}

static void testPlayerRetroRec() {
    std::printf("[player/retro/rec]\n");
    Rig r; r.m.addChannel(63, CH_MASTER);
    r.m.addChannel(7, CH_AUDIO); r.m.setInput(7, IN_PLAYER, 0, 0);
    std::vector<int16_t> pcm(2 * 24000);
    for (int i = 0; i < 24000; ++i) { pcm[2 * i] = 16384; pcm[2 * i + 1] = 16384; }  // 0.5 detik, 0.5
    r.m.loadPlayer(pcm.data(), 24000, 48000);
    auto o = r.run(4800, 0.f);
    CHECK(peakCh(o, 0, 0, 4800) < 1e-6f, "player berhenti");
    r.m.playerPlay(true);
    auto p = r.run(9600, 0.f);
    CHECK(std::fabs(peakCh(p, 0, 960, 4800) - 0.5f) < 0.02f, "player memutar %f", peakCh(p, 0, 960, 4800));
    r.run(24000, 0.f);
    CHECK(!r.m.playerPlaying(), "player berhenti di akhir");
    r.m.playerLoop(true); r.m.playerSeek(0); r.m.playerPlay(true);
    r.run(48000, 0.f);
    CHECK(r.m.playerPlaying(), "loop terus");
    r.m.playerPlay(false);

    // retro: rekam 3 detik sinus lalu baca balik
    CHECK(r.m.retroEnable(true), "retro on");
    r.m.setChannelParam(7, CP_MUTE, 1.f);
    r.m.addChannel(0, CH_AUDIO); r.m.setInput(0, IN_HW, 0, 0); r.m.setChannelParam(0, CP_STEREO, 1.f);
    r.run(144000, 0.5f, 440.f);
    const int avail = r.m.retroBegin();
    CHECK(avail == 144000, "retro tersedia %d", avail);
    std::vector<int16_t> buf(2 * 4800);
    const int got = r.m.retroRead(100000, buf.data(), 4800);
    CHECK(got == 4800, "retro baca");
    int mx = 0; for (int i = 0; i < 4800; ++i) mx = std::max(mx, (int)std::abs(buf[2 * i]));
    CHECK(std::abs(mx - 16384) < 700, "level retro %d", mx);
    CHECK(r.m.retroRead(avail, buf.data(), 10) == 0, "di luar batas");
    r.m.retroEnable(false);
    CHECK(r.m.retroBegin() == 0, "retro off");

    // rec tap
    CHECK(r.m.recStart(-1), "rec start");
    r.run(9600, 0.5f, 440.f);
    std::vector<float> rb(2 * 20000);
    const int rn = r.m.recRead(rb.data(), 20000);
    CHECK(rn == 9600, "rec frame %d", rn);
    CHECK(r.m.recOverruns() == 0, "tanpa overrun");
    r.m.recStop();
    CHECK(r.m.recRead(rb.data(), 100) == 0, "rec berhenti");
}

static void testAppAudio() {
    std::printf("[app audio]\n");
    Rig r; r.m.addChannel(63, CH_MASTER);
    r.m.addChannel(4, CH_AUDIO); r.m.setInput(4, IN_APP, 0, 0);
    std::vector<float> a(2 * 4800, 0.25f);
    CHECK(r.m.pushAppAudio(a.data(), 4800) == 4800, "push");
    auto o = r.run(4800, 0.f);
    CHECK(std::fabs(peakCh(o, 0, 100, 4700) - 0.25f) < 0.01f, "app audio masuk: %f", peakCh(o, 0, 100, 4700));
    auto o2 = r.run(960, 0.f);  // underrun -> senyap, tanpa crash
    CHECK(peakCh(o2, 0, 100, 960) < 1e-6f, "underrun senyap");
    // sumber tambahan di atas input lain
    r.m.setInput(4, IN_NONE, 0, SRC_APP);
    r.m.pushAppAudio(a.data(), 4800);
    auto o3 = r.run(4800, 0.f);
    CHECK(std::fabs(peakCh(o3, 0, 100, 4700) - 0.25f) < 0.01f, "extra mask APP");
}

static void testFuzzThreads() {
    std::printf("[fuzz] ubah struktur sambil render (multi-thread)\n");
    Rig r; r.m.addChannel(63, CH_MASTER);
    r.m.setActive(true);
    std::atomic<bool> stop{false};
    std::thread audio([&] {
        std::vector<float> hw(2 * 192, 0.1f), out(2 * 192);
        uint32_t seed = 1;
        while (!stop.load()) {
            for (int i = 0; i < 192; ++i) { hw[2 * i] = randBipolar(seed) * 0.5f; hw[2 * i + 1] = randBipolar(seed) * 0.5f; }
            r.m.render(hw.data(), 2, out.data(), 192);
            for (float v : out) if (!std::isfinite(v) || std::fabs(v) > 1.0f) { std::printf("  nilai buruk %f\n", v); std::abort(); }
            std::this_thread::sleep_for(std::chrono::microseconds(200));
        }
    });
    uint32_t seed = 7;
    for (int it = 0; it < 3000; ++it) {
        const int id = (int)(xorshift32(seed) % 20);
        switch (xorshift32(seed) % 9) {
            case 0: r.m.addChannel(id, (int)(xorshift32(seed) % 3)); break;
            case 1: r.m.removeChannel(id); break;
            case 2: r.m.setInsert(id, (int)(xorshift32(seed) % kMaxInserts), (int)(xorshift32(seed) % (FX_COUNT + 1)) - 1); break;
            case 3: r.m.setFxParam(id, (int)(xorshift32(seed) % 4), (int)(xorshift32(seed) % 12), randBipolar(seed) * 100.f); break;
            case 4: r.m.setInsertFlags(id, (int)(xorshift32(seed) % 4), xorshift32(seed) & 1, (int)(xorshift32(seed) % 20) - 1); break;
            case 5: r.m.setInput(id, (int)(xorshift32(seed) % 6), (int)(xorshift32(seed) % 10), (int)(xorshift32(seed) % 8)); break;
            case 6: r.m.setChannelParam(id, (int)(xorshift32(seed) % 16), randBipolar(seed) * 70.f); break;
            case 7: r.m.setSend(id, (int)(xorshift32(seed) % 4), (int)(xorshift32(seed) % 10) - 1, randBipolar(seed) * 30.f, xorshift32(seed) & 1, xorshift32(seed) & 1); break;
            default: {
                std::vector<float> d(2000, 0.2f);
                r.m.loadPad((int)(xorshift32(seed) % kNumPads), d.data(), 2000, 1, 48000);
                r.m.triggerPad((int)(xorshift32(seed) % kNumPads), 1.f);
                float ml[3 * kMaxChannels]; r.m.readMeters(ml);
            }
        }
    }
    stop.store(true);
    audio.join();
    CHECK(true, "selesai tanpa crash");
    r.m.setActive(false);
}

int main() {
    enableFlushToZero();
    testFaderPan();
    testMasterSafety();
    testSolo();
    testRouting();
    testSidechain();
    testInsertLatencyBypass();
    testPads();
    testPlayerRetroRec();
    testAppAudio();
    testFuzzThreads();
    std::printf("\n%d lulus, %d gagal\n", g_pass, g_fail);
    return g_fail ? 1 : 0;
}
