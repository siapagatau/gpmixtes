// GP Mix (Android) - inti DSP: utilitas, filter, delay line, kelas dasar Effect.
// Header-only dan TANPA dependensi Android, supaya bisa diuji di host (g++).
#pragma once
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>

namespace gp {

constexpr float kPi = 3.14159265358979323846f;
constexpr float kTwoPi = 6.28318530717958647692f;
constexpr int kBlock = 256;     // chunk maksimum (frame) yang diproses sekaligus
constexpr int kMaxParams = 48;  // parameter maksimum per efek

inline float dbToLin(float db) { return std::pow(10.f, db * 0.05f); }
inline float linToDb(float x) { return 20.f * std::log10(x > 1e-9f ? x : 1e-9f); }
inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
// Koefisien one-pole untuk konstanta waktu 'ms'. ms=0 -> instan (koef 0).
inline float timeCoef(float ms, float sr) {
    const float s = ms * 0.001f * sr;
    return s < 1.f ? 0.f : std::exp(-1.f / s);
}
inline float lerpf(float a, float b, float t) { return a + (b - a) * t; }
inline float sanitize(float x) { return std::isfinite(x) ? x : 0.f; }

// Matikan denormal di thread audio (penting di CPU ARM/x86 supaya tidak lonjakan CPU).
void enableFlushToZero();

// ---------------------------------------------------------------------------
// Deskriptor parameter: satu sumber kebenaran untuk rentang/default dan untuk UI.
// ---------------------------------------------------------------------------
enum ParamKind { PK_FLOAT = 0, PK_TOGGLE = 1, PK_CHOICE = 2 };

struct ParamDesc {
    const char* group;    // judul seksi di UI ("" = tanpa judul)
    const char* name;     // nama (tetap Inggris, sesuai manual)
    int kind;
    float mn, mx, def;
    const char* unit;     // "dB", "ms", "Hz", "%", ...
    const char* choices;  // untuk PK_CHOICE: "A,B,C"
};

constexpr int countChoices(const char* s) {
    int n = 1;
    for (; *s; ++s) if (*s == ',') ++n;
    return n;
}
// Pembantu penulisan deskriptor.
constexpr ParamDesc F(const char* name, float mn, float mx, float def, const char* unit = "",
                      const char* group = "") {
    return ParamDesc{group, name, PK_FLOAT, mn, mx, def, unit, ""};
}
constexpr ParamDesc T(const char* name, bool def, const char* group = "") {
    return ParamDesc{group, name, PK_TOGGLE, 0.f, 1.f, def ? 1.f : 0.f, "", ""};
}
constexpr ParamDesc C(const char* name, const char* choices, int def, const char* group = "") {
    return ParamDesc{group, name, PK_CHOICE, 0.f, (float)(countChoices(choices) - 1), (float)def, "", choices};
}

struct FxContext {
    float sr = 48000.f;
    float bpm = 120.f;
    bool duckEnabled = true;
    const float* keyL = nullptr;  // sinyal key (side chain) dari channel lain; null = internal
    const float* keyR = nullptr;
};

// Pilihan nilai not tempo (dipakai Delay, Tremolo, Auto Pan). Urutan = indeks parameter.
#define GP_NOTE_CHOICES "4/1,2/1,1/1,1/2,1/4,1/8,1/16,1/32,1/2T,1/4T,1/8T,1/16T,1/2D,1/4D,1/8D,1/16D"
float noteBeats(int idx);  // panjang not dalam beat (seperempat)

class Effect {
public:
    Effect(const ParamDesc* d, int n) : desc_(d), nDesc_(n) {
        for (int i = 0; i < kMaxParams; ++i) p_[i].store(i < n ? d[i].def : 0.f, std::memory_order_relaxed);
    }
    virtual ~Effect() = default;

    // Dipanggil di luar thread audio. Mengalokasi buffer sesuai sample rate.
    void setSampleRate(float sr) {
        sr_ = sr < 8000.f ? 8000.f : sr;
        prepare();
        reset();
    }
    void setParam(int i, float v) {
        if (i < 0 || i >= nDesc_ || !std::isfinite(v)) return;
        p_[i].store(clampf(v, desc_[i].mn, desc_[i].mx), std::memory_order_relaxed);
    }
    float getParam(int i) const { return (i >= 0 && i < nDesc_) ? P(i) : 0.f; }
    int paramCount() const { return nDesc_; }
    const ParamDesc* desc() const { return desc_; }
    void resetParams() {
        for (int i = 0; i < nDesc_; ++i) p_[i].store(desc_[i].def, std::memory_order_relaxed);
    }

    virtual void reset() {}
    // l/r diproses di tempat (in-place), n <= kBlock.
    virtual void process(float* l, float* r, int n, const FxContext& c) = 0;

    int latency() const { return latency_.load(std::memory_order_relaxed); }
    float gainReductionDb() const { return gr_.load(std::memory_order_relaxed); }

protected:
    virtual void prepare() {}
    float P(int i) const { return p_[i].load(std::memory_order_relaxed); }
    int PI(int i) const { return (int)(P(i) + 0.5f); }
    bool PB(int i) const { return P(i) > 0.5f; }
    void setLatency(int s) { latency_.store(s, std::memory_order_relaxed); }
    void setGr(float db) { gr_.store(db, std::memory_order_relaxed); }

    float sr_ = 48000.f;

private:
    const ParamDesc* desc_;
    int nDesc_;
    std::atomic<float> p_[kMaxParams];
    std::atomic<int> latency_{0};
    std::atomic<float> gr_{0.f};
};

// ---------------------------------------------------------------------------
// Biquad (RBJ cookbook), double supaya stabil untuk frekuensi rendah.
// ---------------------------------------------------------------------------
struct Biquad {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1 = 0, z2 = 0;

    void reset() { z1 = z2 = 0; }
    inline float process(float xf) {
        const double x = xf;
        const double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        if (std::fabs(z1) < 1e-30) z1 = 0;
        if (std::fabs(z2) < 1e-30) z2 = 0;
        return (float)y;
    }
    void setCoefs(double nb0, double nb1, double nb2, double na0, double na1, double na2) {
        b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0;
        a1 = na1 / na0; a2 = na2 / na0;
    }
    static void prep(double fs, double f, double q, double& cw, double& sw, double& alpha) {
        f = std::min(std::max(f, 5.0), fs * 0.49);
        q = std::max(q, 0.05);
        const double w0 = 2.0 * 3.14159265358979323846 * f / fs;
        cw = std::cos(w0);
        sw = std::sin(w0);
        alpha = sw / (2.0 * q);
    }
    void lowpass(double fs, double f, double q) {
        double cw, sw, al; prep(fs, f, q, cw, sw, al);
        setCoefs((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + al, -2 * cw, 1 - al);
    }
    void highpass(double fs, double f, double q) {
        double cw, sw, al; prep(fs, f, q, cw, sw, al);
        setCoefs((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + al, -2 * cw, 1 - al);
    }
    void bandpass(double fs, double f, double q) {  // gain puncak 0 dB
        double cw, sw, al; prep(fs, f, q, cw, sw, al);
        setCoefs(al, 0, -al, 1 + al, -2 * cw, 1 - al);
    }
    void notch(double fs, double f, double q) {
        double cw, sw, al; prep(fs, f, q, cw, sw, al);
        setCoefs(1, -2 * cw, 1, 1 + al, -2 * cw, 1 - al);
    }
    void peaking(double fs, double f, double q, double gainDb) {
        double cw, sw, al; prep(fs, f, q, cw, sw, al);
        const double A = std::pow(10.0, gainDb / 40.0);
        setCoefs(1 + al * A, -2 * cw, 1 - al * A, 1 + al / A, -2 * cw, 1 - al / A);
    }
    void lowShelf(double fs, double f, double q, double gainDb) {
        double cw, sw, al; prep(fs, f, q, cw, sw, al);
        const double A = std::pow(10.0, gainDb / 40.0);
        const double t = 2 * std::sqrt(A) * al;
        setCoefs(A * ((A + 1) - (A - 1) * cw + t), 2 * A * ((A - 1) - (A + 1) * cw),
                 A * ((A + 1) - (A - 1) * cw - t), (A + 1) + (A - 1) * cw + t,
                 -2 * ((A - 1) + (A + 1) * cw), (A + 1) + (A - 1) * cw - t);
    }
    void highShelf(double fs, double f, double q, double gainDb) {
        double cw, sw, al; prep(fs, f, q, cw, sw, al);
        const double A = std::pow(10.0, gainDb / 40.0);
        const double t = 2 * std::sqrt(A) * al;
        setCoefs(A * ((A + 1) + (A - 1) * cw + t), -2 * A * ((A - 1) + (A + 1) * cw),
                 A * ((A + 1) + (A - 1) * cw - t), (A + 1) - (A - 1) * cw + t,
                 2 * ((A - 1) - (A + 1) * cw), (A + 1) - (A - 1) * cw - t);
    }
};

// Q tiap stage Butterworth orde genap (2,4,6,8) -> 12/24/36/48 dB per oktaf.
inline double butterQ(int order, int stage) {
    return 1.0 / (2.0 * std::sin((2 * stage + 1) * 3.14159265358979323846 / (2.0 * order)));
}

// Kaskade high-pass / low-pass Butterworth stereo (1..4 stage).
struct FilterCascade {
    Biquad s[4][2];
    int stages = 1;
    void reset() { for (auto& a : s) { a[0].reset(); a[1].reset(); } }
    // slopeIdx: 0=12, 1=24, 2=36, 3=48 dB/oktaf
    void set(bool high, double fs, double f, int slopeIdx) {
        stages = clampi(slopeIdx, 0, 3) + 1;
        const int order = stages * 2;
        for (int k = 0; k < stages; ++k) {
            const double q = butterQ(order, k);
            for (int c = 0; c < 2; ++c) {
                if (high) s[k][c].highpass(fs, f, q); else s[k][c].lowpass(fs, f, q);
            }
        }
    }
    inline void process(float* l, float* r, int n) {
        for (int k = 0; k < stages; ++k)
            for (int i = 0; i < n; ++i) { l[i] = s[k][0].process(l[i]); r[i] = s[k][1].process(r[i]); }
    }
};

// ---------------------------------------------------------------------------
// Delay line (ring buffer) dengan pembacaan fraksional.
// ---------------------------------------------------------------------------
struct DelayLine {
    std::vector<float> buf;
    int mask = 0, w = 0;
    void init(int maxSamples) {
        int sz = 1;
        while (sz < maxSamples + 4) sz <<= 1;
        buf.assign(sz, 0.f);
        mask = sz - 1;
        w = 0;
    }
    void clear() { std::fill(buf.begin(), buf.end(), 0.f); w = 0; }
    inline void write(float x) { buf[w] = x; w = (w + 1) & mask; }
    // d = jarak dalam sampel dari sampel yang terakhir ditulis (d>=0; d=0 -> sampel terakhir).
    inline float read(float d) const {
        const float p = (float)(w - 1) - d;
        const int i0 = (int)std::floor(p);
        const float f = p - (float)i0;
        const float a = buf[i0 & mask], b = buf[(i0 + 1) & mask];
        return a + (b - a) * f;
    }
    inline float readInt(int d) const { return buf[(w - 1 - d) & mask]; }
};

struct OnePoleLP {
    float a = 0.f, z = 0.f;  // y = (1-a)*x + a*z
    void setCutoff(float fc, float sr) { a = std::exp(-kTwoPi * clampf(fc, 5.f, sr * 0.45f) / sr); }
    inline float process(float x) { z = (1.f - a) * x + a * z; return z; }
    void reset() { z = 0.f; }
};
struct OnePoleHP {
    OnePoleLP lp;
    void setCutoff(float fc, float sr) { lp.setCutoff(fc, sr); }
    inline float process(float x) { return x - lp.process(x); }
    void reset() { lp.reset(); }
};

// Smoothing parameter per-sampel (anti zipper).
struct Smooth {
    float cur = 0.f;
    bool init = false;
    inline float step(float target, float coef) {
        if (!init) { cur = target; init = true; }
        cur = target + (cur - target) * coef;
        return cur;
    }
};

// LFO bentuk gelombang, phase 0..1 -> -1..1. shape: 0 Sine,1 Triangle,2 Square,3 Saw Up,4 Saw Down
inline float lfoShape(int shape, float p) {
    switch (shape) {
        case 0: return std::sin(kTwoPi * p);
        case 1: { float t = p + 0.25f; t -= std::floor(t); return 4.f * std::fabs(t - 0.5f) - 1.f; }
        case 2: return p < 0.5f ? 1.f : -1.f;
        case 3: return 2.f * p - 1.f;
        default: return 1.f - 2.f * p;
    }
}

inline uint32_t xorshift32(uint32_t& s) {
    s ^= s << 13; s ^= s >> 17; s ^= s << 5;
    return s;
}
inline float randBipolar(uint32_t& s) { return (float)((int32_t)xorshift32(s)) * (1.f / 2147483648.f); }

}  // namespace gp
