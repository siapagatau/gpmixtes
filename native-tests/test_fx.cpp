// Tes numerik efek GP Mix di host. Build: lihat native-tests/run.sh
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <string>
#include <vector>

#include "fx/fx_dyn.h"
#include "fx/fx_eq.h"
#include "fx/fx_misc.h"
#include "fx/fx_registry.h"
#include "fx/fx_time.h"

using namespace gp;

static int g_fail = 0, g_pass = 0;
#define CHECK(cond, ...)                                              \
    do {                                                              \
        if (cond) { ++g_pass; }                                       \
        else { ++g_fail; std::printf("  FAIL %s:%d  %s  -> ", __FILE__, __LINE__, #cond); std::printf(__VA_ARGS__); std::printf("\n"); } \
    } while (0)

static const float SR = 48000.f;

static void run(Effect& e, std::vector<float>& l, std::vector<float>& r, const FxContext& c) {
    const int n = (int)l.size();
    for (int i = 0; i < n; i += kBlock) {
        const int m = std::min(kBlock, n - i);
        e.process(l.data() + i, r.data() + i, m, c);
    }
}
static FxContext ctx() { FxContext c; c.sr = SR; return c; }
static std::vector<float> sine(float hz, float db, int n) {
    std::vector<float> v(n);
    const float a = dbToLin(db);
    for (int i = 0; i < n; ++i) v[i] = a * std::sin(kTwoPi * hz * i / SR);
    return v;
}
static float rmsDb(const std::vector<float>& v, int from, int to) {
    double s = 0;
    for (int i = from; i < to; ++i) s += (double)v[i] * v[i];
    return linToDb((float)std::sqrt(s / (to - from)));
}
static float peakOf(const std::vector<float>& v, int from, int to) {
    float p = 0;
    for (int i = from; i < to; ++i) p = std::max(p, std::fabs(v[i]));
    return p;
}
// Gain (dB) efek pada sinus
static float toneGain(Effect& e, float hz, float inDb = -20.f) {
    auto l = sine(hz, inDb, 24000), r = l;
    run(e, l, r, ctx());
    return rmsDb(l, 12000, 24000) - (inDb - 3.0103f);
}

static void testAllSane() {
    std::printf("[sane] semua efek stabil, tanpa NaN/Inf\n");
    uint32_t seed = 99;
    for (int t = 0; t < fxCount(); ++t) {
        const FxInfo* fi = fxInfo(t);
        for (int mode = 0; mode < 3; ++mode) {
            auto e = makeEffect(t, SR);
            CHECK(e != nullptr, "makeEffect %d", t);
            if (mode == 2) {  // parameter ekstrem acak
                for (int i = 0; i < fi->nParams; ++i) {
                    const ParamDesc& p = fi->params[i];
                    e->setParam(i, (xorshift32(seed) & 1) ? p.mn : p.mx);
                }
            }
            const int n = 48000;
            std::vector<float> l(n), r(n);
            for (int i = 0; i < n; ++i) {
                if (mode == 0) { l[i] = r[i] = 0.f; }
                else { l[i] = randBipolar(seed) * 0.9f; r[i] = randBipolar(seed) * 0.9f; }
            }
            if (mode == 0) l[100] = r[100] = 1.f;  // impuls
            FxContext c = ctx();
            std::vector<float> kl(l), kr(r);
            c.keyL = kl.data(); c.keyR = kr.data();
            for (int i = 0; i < n; i += kBlock) {
                const int m = std::min(kBlock, n - i);
                c.keyL = kl.data() + i; c.keyR = kr.data() + i;
                e->process(l.data() + i, r.data() + i, m, c);
            }
            bool ok = true; float mx = 0;
            for (int i = 0; i < n; ++i) { if (!std::isfinite(l[i]) || !std::isfinite(r[i])) ok = false; mx = std::max(mx, std::max(std::fabs(l[i]), std::fabs(r[i]))); }
            CHECK(ok, "%s mode %d menghasilkan NaN/Inf", fi->name, mode);
            CHECK(mx < 200.f, "%s mode %d level meledak: %f", fi->name, mode, mx);
        }
    }
}

static void testDescriptors() {
    std::printf("[desc] deskriptor parameter\n");
    for (int t = 0; t < fxCount(); ++t) {
        const FxInfo* fi = fxInfo(t);
        CHECK(fi->nParams > 0 && fi->nParams <= kMaxParams, "%s nParams %d", fi->name, fi->nParams);
        for (int i = 0; i < fi->nParams; ++i) {
            const ParamDesc& p = fi->params[i];
            CHECK(p.def >= p.mn && p.def <= p.mx, "%s/%s default di luar rentang", fi->name, p.name);
            CHECK(p.mn < p.mx || p.kind == PK_CHOICE, "%s/%s rentang", fi->name, p.name);
            if (p.kind == PK_CHOICE) CHECK(p.mx == countChoices(p.choices) - 1, "%s/%s pilihan", fi->name, p.name);
        }
    }
    const std::string d = describeEffects();
    int e = 0, p = 0;
    size_t pos = 0;
    while (pos < d.size()) {
        const size_t nl = d.find('\n', pos);
        const std::string line = d.substr(pos, nl - pos);
        if (line[0] == 'E') ++e; else if (line[0] == 'P') ++p;
        int bars = 0; for (char ch : line) bars += ch == '|';
        CHECK(bars == (line[0] == 'E' ? 4 : 8), "format baris: %s", line.c_str());
        pos = nl + 1;
    }
    CHECK(e == fxCount(), "jumlah efek %d", e);
    std::printf("  %d efek, %d parameter\n", e, p);
}

static void testClamp() {
    std::printf("[clamp] setParam menjepit nilai\n");
    auto e = makeEffect(FX_COMPRESSOR, SR);
    e->setParam(Compressor::THR, 50.f);
    CHECK(e->getParam(Compressor::THR) == 0.f, "thr");
    e->setParam(Compressor::THR, -500.f);
    CHECK(e->getParam(Compressor::THR) == -60.f, "thr min");
    e->setParam(Compressor::THR, NAN);
    CHECK(e->getParam(Compressor::THR) == -60.f, "NaN diabaikan");
    e->setParam(99, 1.f);  // di luar indeks: tidak crash
}

static void testEQ() {
    std::printf("[eq] respons frekuensi\n");
    {
        auto e = makeEffect(FX_EQ, SR);  // semua flat
        CHECK(std::fabs(toneGain(*e, 1000)) < 0.1f, "flat %f", toneGain(*e, 1000));
    }
    {
        auto e = makeEffect(FX_EQ, SR);
        const int o = EQ::B0 + 2 * 5;  // band 3 bell @800
        e->setParam(o + 1, 1000.f); e->setParam(o + 2, 12.f); e->setParam(o + 3, 1.f);
        const float g = toneGain(*e, 1000);
        CHECK(std::fabs(g - 12.f) < 0.3f, "bell +12 dB @1k = %f", g);
        auto e2 = makeEffect(FX_EQ, SR);
        e2->setParam(o + 1, 1000.f); e2->setParam(o + 2, 12.f);
        CHECK(std::fabs(toneGain(*e2, 100)) < 0.8f, "bell tidak memengaruhi 100 Hz");
    }
    {
        auto e = makeEffect(FX_EQ, SR);
        e->setParam(EQ::HP_ON, 1); e->setParam(EQ::HP_F, 1000); e->setParam(EQ::HP_S, 1);  // 24 dB/okt
        const float g500 = toneGain(*e, 500);
        auto e2 = makeEffect(FX_EQ, SR);
        e2->setParam(EQ::HP_ON, 1); e2->setParam(EQ::HP_F, 1000); e2->setParam(EQ::HP_S, 1);
        const float g1k = toneGain(*e2, 1000), g4k = toneGain(*e2, 4000);
        CHECK(std::fabs(g1k + 3.f) < 0.6f, "HP -3 dB di fc: %f", g1k);
        CHECK(g500 < -20.f && g500 > -28.f, "HP 24dB/okt @500 ~ -24: %f", g500);
        CHECK(std::fabs(g4k) < 0.3f, "HP lolos 4k %f", g4k);
    }
    {
        auto e = makeEffect(FX_EQ, SR);
        e->setParam(EQ::LP_ON, 1); e->setParam(EQ::LP_F, 2000); e->setParam(EQ::LP_S, 3);  // 48 dB
        const float g = toneGain(*e, 8000);
        CHECK(g < -60.f, "LP 48dB/okt @8k %f", g);
    }
    {
        auto e = makeEffect(FX_EQ, SR);  // B1 low shelf @80Hz +12
        e->setParam(EQ::B0 + 2, 12.f);
        CHECK(toneGain(*e, 30) > 9.f, "low shelf");
    }
}

static void testCompressor() {
    std::printf("[comp] kompresi\n");
    auto e = makeEffect(FX_COMPRESSOR, SR);
    e->setParam(Compressor::THR, -20); e->setParam(Compressor::RATIO, 4); e->setParam(Compressor::KNEE, 0);
    e->setParam(Compressor::ATT, 1); e->setParam(Compressor::REL, 50);
    auto l = sine(1000, -8.f, 48000), r = l;  // puncak -8 dBFS: 12 dB di atas thr
    run(*e, l, r, ctx());
    const float pk = linToDb(peakOf(l, 30000, 48000));
    // out = thr + over/ratio = -20 + 12/4 = -17 dBFS
    CHECK(std::fabs(pk + 17.f) < 0.7f, "puncak keluar %f (harap -17)", pk);
    CHECK(std::fabs(e->gainReductionDb() - 9.f) < 1.0f, "GR %f", e->gainReductionDb());
    // di bawah threshold tidak berubah
    auto e2 = makeEffect(FX_COMPRESSOR, SR);
    e2->setParam(Compressor::KNEE, 0);
    CHECK(std::fabs(toneGain(*e2, 1000, -40.f)) < 0.1f, "di bawah thr");
    // makeup
    auto e3 = makeEffect(FX_COMPRESSOR, SR);
    e3->setParam(Compressor::MAKEUP, 6.f);
    CHECK(std::fabs(toneGain(*e3, 1000, -50.f) - 6.f) < 0.1f, "makeup");
}

static void testGate() {
    std::printf("[gate] noise gate\n");
    auto e = makeEffect(FX_GATE, SR);
    e->setParam(Gate::THR, -40); e->setParam(Gate::RANGE, -60); e->setParam(Gate::LOOK, 0);
    e->setParam(Gate::HOLD, 10); e->setParam(Gate::REL, 20);
    auto quiet = sine(1000, -60, 24000), qr = quiet;
    run(*e, quiet, qr, ctx());
    CHECK(rmsDb(quiet, 12000, 24000) < -60 - 3 - 40.f + 1.f, "sinyal pelan ditutup: %f", rmsDb(quiet, 12000, 24000));
    auto loud = sine(1000, -10, 24000), lr = loud;
    run(*e, loud, lr, ctx());
    CHECK(std::fabs(rmsDb(loud, 12000, 24000) - (-10 - 3.01f)) < 0.5f, "sinyal keras dibuka %f", rmsDb(loud, 12000, 24000));
    auto e2 = makeEffect(FX_GATE, SR);
    e2->setParam(Gate::LOOK, 5);
    auto x = sine(1000, -10, 4800), xr = x;
    run(*e2, x, xr, ctx());
    CHECK(e2->latency() == 240, "latency lookahead %d", e2->latency());
}

static void testLimiter() {
    std::printf("[limiter] batas puncak\n");
    auto e = makeEffect(FX_LIMITER, SR);
    e->setParam(Limiter::GAIN, 12);  // dorong keras
    uint32_t seed = 5;
    std::vector<float> l(96000), r(96000);
    for (int i = 0; i < 96000; ++i) { const float burst = (i / 8000) % 2 ? 1.5f : 0.1f; l[i] = randBipolar(seed) * burst; r[i] = randBipolar(seed) * burst; }
    run(*e, l, r, ctx());
    const float ceil = dbToLin(-0.3f);
    CHECK(peakOf(l, 0, 96000) <= ceil + 1e-6f, "puncak %f > ceiling %f", peakOf(l, 0, 96000), ceil);
    CHECK(peakOf(r, 0, 96000) <= ceil + 1e-6f, "puncak R");
    CHECK(e->latency() == 96, "latency %d", e->latency());
    // sinyal pelan lolos tanpa perubahan (selain delay)
    auto e2 = makeEffect(FX_LIMITER, SR);
    e2->setParam(Limiter::LOOK, 0);
    CHECK(std::fabs(toneGain(*e2, 1000, -20.f)) < 0.01f, "transparan di bawah ceiling");
    // lookahead 0 tetap mematuhi ceiling
    auto e3 = makeEffect(FX_LIMITER, SR);
    e3->setParam(Limiter::LOOK, 0); e3->setParam(Limiter::GAIN, 20);
    auto a = sine(500, 0, 9600), b = a;
    run(*e3, a, b, ctx());
    CHECK(peakOf(a, 0, 9600) <= ceil + 1e-6f, "lookahead 0");
}

static void testDucking() {
    std::printf("[duck] ducking\n");
    auto e = makeEffect(FX_DUCKING, SR);
    e->setParam(Ducking::ATT, 5); e->setParam(Ducking::REL, 100);
    auto music = sine(440, -10, 48000), mr = music;
    auto voice = sine(200, -10, 48000);
    FxContext c = ctx();
    c.keyL = voice.data(); c.keyR = voice.data();
    for (int i = 0; i < 48000; i += kBlock) {
        const int m = std::min(kBlock, 48000 - i);
        c.keyL = voice.data() + i; c.keyR = voice.data() + i;
        e->process(music.data() + i, mr.data() + i, m, c);
    }
    CHECK(std::fabs(rmsDb(music, 30000, 48000) - (-10 - 3.01f - 12.f)) < 1.0f, "turun 12 dB: %f", rmsDb(music, 30000, 48000));
    // tanpa key -> tidak mengubah
    auto e2 = makeEffect(FX_DUCKING, SR);
    CHECK(std::fabs(toneGain(*e2, 440)) < 0.01f, "tanpa key");
    // ducking dimatikan global
    auto e3 = makeEffect(FX_DUCKING, SR);
    auto m3 = sine(440, -10, 24000), r3 = m3;
    FxContext c3 = ctx(); c3.duckEnabled = false;
    std::vector<float> k3 = sine(200, -10, 24000);
    for (int i = 0; i < 24000; i += kBlock) { const int m = std::min(kBlock, 24000 - i); c3.keyL = k3.data() + i; c3.keyR = k3.data() + i; e3->process(m3.data() + i, r3.data() + i, m, c3); }
    CHECK(std::fabs(rmsDb(m3, 12000, 24000) - (-13.01f)) < 0.2f, "duck off");
}

static void testDelay() {
    std::printf("[delay] waktu & feedback\n");
    auto e = makeEffect(FX_DELAY, SR);
    e->setParam(Delay::MODE, 1); e->setParam(Delay::TIME, 100); e->setParam(Delay::MIX, 100);
    e->setParam(Delay::FB, 50); e->setParam(Delay::HICUT, 20000); e->setParam(Delay::LOCUT, 20);
    std::vector<float> l(48000, 0.f), r(48000, 0.f);
    l[0] = 1.f;
    run(*e, l, r, ctx());
    int first = -1;
    for (int i = 0; i < 48000; ++i) if (std::fabs(l[i]) > 0.3f) { first = i; break; }
    CHECK(std::abs(first - 4800) <= 2, "echo pertama di %d (harap 4800)", first);
    CHECK(std::fabs(l[9600]) > 0.40f && std::fabs(l[9600]) < 0.55f, "echo kedua ~0.5: %f", l[9600]);
    // tempo sync: 1/4 @ 120 bpm = 500 ms
    auto e2 = makeEffect(FX_DELAY, SR);
    e2->setParam(Delay::MODE, 1); e2->setParam(Delay::SYNC, 1); e2->setParam(Delay::NOTE, 4); e2->setParam(Delay::MIX, 100);
    e2->setParam(Delay::HICUT, 20000); e2->setParam(Delay::LOCUT, 20);
    std::vector<float> a(48000, 0.f), b(48000, 0.f); a[0] = 1.f;
    FxContext c = ctx(); c.bpm = 120.f;
    run(*e2, a, b, c);
    int f2 = -1; for (int i = 0; i < 48000; ++i) if (std::fabs(a[i]) > 0.3f) { f2 = i; break; }
    CHECK(std::abs(f2 - 24000) <= 2, "sync 1/4@120 = %d", f2);
}

static void testReverb() {
    std::printf("[reverb] RT60 dan kestabilan\n");
    for (int type = 0; type < 3; ++type) {
        auto e = makeEffect(FX_REVERB, SR);
        e->setParam(Reverb::TYPE, (float)type); e->setParam(Reverb::MIX, 100);
        e->setParam(Reverb::DAMP, 0); e->setParam(Reverb::MOD, 0); e->setParam(Reverb::PRE, 0);
        e->setParam(Reverb::DECAY, 2.0f); e->setParam(Reverb::LOCUT, 20); e->setParam(Reverb::HICUT, 20000);
        const int n = (int)(SR * 5);
        std::vector<float> l(n, 0.f), r(n, 0.f);
        l[0] = r[0] = 1.f;
        run(*e, l, r, ctx());
        // integral Schroeder terbalik
        std::vector<double> edc(n);
        double s = 0;
        for (int i = n - 1; i >= 0; --i) { s += (double)l[i] * l[i] + (double)r[i] * r[i]; edc[i] = s; }
        auto tAt = [&](double db) { for (int i = 0; i < n; ++i) if (10.0 * std::log10(edc[i] / edc[0]) < db) return i / SR; return (float)n / SR; };
        const double t60 = (tAt(-25.0) - tAt(-5.0)) * 3.0;  // T20 x3
        std::printf("  tipe %d: RT60 terukur %.2f s (param 2.0)\n", type, t60);
        CHECK(t60 > 1.2 && t60 < 3.0, "RT60 tipe %d = %f", type, t60);
        CHECK(edc[0] > 0.01, "ada energi");
    }
    // Decay panjang + damping tinggi tetap stabil
    auto e = makeEffect(FX_REVERB, SR);
    e->setParam(Reverb::DECAY, 20); e->setParam(Reverb::DAMP, 0); e->setParam(Reverb::SIZE, 100); e->setParam(Reverb::MIX, 100);
    uint32_t seed = 3;
    std::vector<float> l(SR * 10), r(SR * 10);
    for (size_t i = 0; i < l.size(); ++i) { l[i] = i < 48000 ? randBipolar(seed) : 0.f; r[i] = l[i]; }
    run(*e, l, r, ctx());
    CHECK(peakOf(l, 0, (int)l.size()) < 10.f, "stabil decay 20s: %f", peakOf(l, 0, (int)l.size()));
    // dry ketika mix 0
    auto e0 = makeEffect(FX_REVERB, SR);
    e0->setParam(Reverb::MIX, 0);
    CHECK(std::fabs(toneGain(*e0, 1000)) < 0.01f, "mix 0 = dry");
}

static void testTransposer() {
    std::printf("[transposer] pitch\n");
    auto zc = [](const std::vector<float>& v, int a, int b) { int c = 0; for (int i = a + 1; i < b; ++i) if (v[i - 1] < 0 && v[i] >= 0) ++c; return c; };
    for (float st : {-12.f, -5.f, 7.f, 12.f}) {
        auto e = makeEffect(FX_TRANSPOSER, SR);
        e->setParam(Transposer::SEMI, st);
        auto l = sine(440, -10, 96000), r = l;
        run(*e, l, r, ctx());
        const int a = 24000, b = 96000;
        const double hz = zc(l, a, b) / ((b - a) / (double)SR);
        const double expect = 440.0 * std::pow(2.0, st / 12.0);
        CHECK(std::fabs(hz - expect) / expect < 0.03, "%+.0f st: %.1f Hz (harap %.1f)", st, hz, expect);
    }
    auto e = makeEffect(FX_TRANSPOSER, SR);
    CHECK(e->latency() == 0, "latency awal");
    auto l = sine(440, -10, 4800), r = l;
    run(*e, l, r, ctx());
    CHECK(e->latency() == 1201, "latency terlapor %d", e->latency());
    CHECK(std::fabs(toneGain(*e, 440) ) < 0.2f, "0 st transparan");
}

static void testMisc() {
    std::printf("[misc] efek lain\n");
    {   // Hum remover
        auto e = makeEffect(FX_HUM, SR);
        e->setParam(HumRemover::MAINS, 0); e->setParam(HumRemover::Q, 30);
        auto hs = sine(50, -20, 96000), hsr = hs;  // notch sempit butuh waktu mengendap
        run(*e, hs, hsr, ctx());
        const float g50 = rmsDb(hs, 80000, 96000) - (-20 - 3.0103f);
        auto e2 = makeEffect(FX_HUM, SR); e2->setParam(HumRemover::MAINS, 0);
        const float g1k = toneGain(*e2, 1000);
        CHECK(g50 < -25.f, "notch 50 Hz: %f", g50);
        CHECK(std::fabs(g1k) < 0.1f, "1k lolos");
        auto e3 = makeEffect(FX_HUM, SR);  // Auto mendeteksi 60 Hz
        auto h = sine(60, -20, 96000), hr = h;
        run(*e3, h, hr, ctx());
        CHECK(rmsDb(h, 80000, 96000) < -50.f, "auto 60 Hz: %f", rmsDb(h, 80000, 96000));
    }
    {   // De-esser: sibilance diturunkan, vokal tidak
        auto e = makeEffect(FX_DEESSER, SR);
        e->setParam(DeEsser::THR, -40);
        const float gs = toneGain(*e, 6500, -20.f);
        auto e2 = makeEffect(FX_DEESSER, SR); e2->setParam(DeEsser::THR, -40);
        const float gv = toneGain(*e2, 300, -20.f);
        CHECK(gs < -6.f, "sibilance turun: %f", gs);
        CHECK(std::fabs(gv) < 0.5f, "vokal utuh: %f", gv);
    }
    {   // Expander menurunkan sinyal pelan
        auto e = makeEffect(FX_EXPANDER, SR);
        e->setParam(Expander::THR, -30); e->setParam(Expander::RATIO, 2); e->setParam(Expander::KNEE, 0); e->setParam(Expander::LOOK, 0);
        const float g = toneGain(*e, 1000, -50.f);  // 20 dB di bawah -> turun ~20 dB
        CHECK(g < -12.f && g > -28.f, "expander %f", g);
    }
    {   // Utility
        auto e = makeEffect(FX_UTILITY, SR);
        e->setParam(Utility::GAIN, 6.f);
        CHECK(std::fabs(toneGain(*e, 1000) - 6.f) < 0.2f, "gain");
        auto m = makeEffect(FX_UTILITY, SR); m->setParam(Utility::MONO, 1); m->setParam(Utility::POLR, 1);
        std::vector<float> a(2000, 0.5f), b(2000, 0.5f);
        run(*m, a, b, ctx());
        CHECK(std::fabs(a[1999]) < 1e-5f, "mono + polaritas R membatalkan: %f", a[1999]);
        auto sw = makeEffect(FX_UTILITY, SR); sw->setParam(Utility::SWAP, 1);
        std::vector<float> c(2000, 1.f), d(2000, 0.f);
        run(*sw, c, d, ctx());
        CHECK(std::fabs(d[1999] - 1.f) < 1e-3f && std::fabs(c[1999]) < 1e-3f, "swap");
    }
    {   // Test tone
        auto e = makeEffect(FX_TESTTONE, SR);
        e->setParam(TestTone::LEVEL, -20); e->setParam(TestTone::CHANNELS, 1);
        std::vector<float> l(9600, 0.f), r(9600, 0.f);
        run(*e, l, r, ctx());
        CHECK(std::fabs(peakOf(l, 0, 9600) - 0.1f) < 0.005f, "level tone %f", peakOf(l, 0, 9600));
        CHECK(peakOf(r, 0, 9600) == 0.f, "kanal kanan sunyi");
    }
    {   // Bitcrusher 1 bit -> hanya nilai diskret
        auto e = makeEffect(FX_BITCRUSHER, SR);
        e->setParam(Bitcrusher::BITS, 2);
        auto l = sine(100, -3, 4800), r = l;
        run(*e, l, r, ctx());
        std::vector<float> vals;
        for (float v : l) { bool f = false; for (float u : vals) if (std::fabs(u - v) < 1e-6f) f = true; if (!f) vals.push_back(v); }
        CHECK(vals.size() <= 4, "2 bit -> %zu level", vals.size());
    }
    {   // Saturator: menambah harmonik / membatasi
        auto e = makeEffect(FX_SATURATOR, SR);
        e->setParam(Saturator::DRIVE, 24); e->setParam(Saturator::AUTO, 0); e->setParam(Saturator::SOFT, 0);
        auto l = sine(200, -6, 24000), r = l;
        run(*e, l, r, ctx());
        CHECK(peakOf(l, 12000, 24000) < 1.2f, "saturasi terbatas %f", peakOf(l, 12000, 24000));
        CHECK(peakOf(l, 12000, 24000) > 0.8f, "drive terdengar");
    }
    {   // Tremolo kedalaman 100% -> minimum ~0
        auto e = makeEffect(FX_TREMOLO, SR);
        e->setParam(Tremolo::SYNC, 0); e->setParam(Tremolo::RATE, 5); e->setParam(Tremolo::DEPTH, 100); e->setParam(Tremolo::SMOOTH, 0);
        std::vector<float> a(48000, 1.f), b(48000, 1.f);
        run(*e, a, b, ctx());
        float mn = 1, mx = 0; for (float v : a) { mn = std::min(mn, v); mx = std::max(mx, v); }
        CHECK(mn < 0.02f && mx > 0.98f, "tremolo %f..%f", mn, mx);
    }
    {   // Auto pan: daya konstan, geser kiri-kanan
        auto e = makeEffect(FX_AUTOPAN, SR);
        e->setParam(AutoPan::SYNC, 0); e->setParam(AutoPan::RATE, 1); e->setParam(AutoPan::SMOOTH, 0);
        std::vector<float> a(48000, 1.f), b(48000, 1.f);
        run(*e, a, b, ctx());
        float maxDiff = 0; for (int i = 0; i < 48000; ++i) maxDiff = std::max(maxDiff, std::fabs(a[i] - b[i]));
        CHECK(maxDiff > 1.0f, "auto pan bergerak %f", maxDiff);
    }
    {   // Chorus & parameter default tidak mengubah level besar
        auto e = makeEffect(FX_CHORUS, SR);
        auto l = sine(500, -12, 48000), r = l;
        run(*e, l, r, ctx());
        CHECK(std::fabs(rmsDb(l, 24000, 48000) + 12.f + 3.f) < 4.f, "level chorus %f", rmsDb(l, 24000, 48000));
    }
}

int main() {
    enableFlushToZero();
    testDescriptors();
    testClamp();
    testAllSane();
    testEQ();
    testCompressor();
    testGate();
    testLimiter();
    testDucking();
    testDelay();
    testReverb();
    testTransposer();
    testMisc();
    std::printf("\n%d lulus, %d gagal\n", g_pass, g_fail);
    return g_fail ? 1 : 0;
}
