// GP Mix (Android) - efek warna, modulasi dan utilitas:
// Saturator, Tremolo, Auto Pan, Utility, Hum Remover, Bitcrusher, Test Tone.
#pragma once
#include "fx_core.h"

namespace gp {

// ---------------------------------------------------------------------------
// 4.19 Saturator (anti-aliasing ADAA orde-1)
// ---------------------------------------------------------------------------
inline constexpr ParamDesc kSatDesc[] = {
    C("Mode", "Tape,Tube,Transformer", 0), F("Drive", 0, 24, 8, "dB"), F("Soften", 0, 100, 35, "%"),
    T("Auto Gain", true),                  F("Output", -12, 12, 0, "dB"), F("Mix", 0, 100, 100, "%"),
};

class Saturator : public Effect {
public:
    enum { MODE, DRIVE, SOFT, AUTO, OUT, MIX };
    Saturator() : Effect(kSatDesc, 6) {}

    void process(float* l, float* r, int n, const FxContext&) override {
        const int mode = PI(MODE);
        const float drive = dbToLin(P(DRIVE)), mix = P(MIX) * 0.01f, outG = dbToLin(P(OUT));
        float comp = 1.f;
        if (PB(AUTO)) {
            const float ref = 0.25f;
            comp = ref / std::max(1e-4f, shaper(mode, ref * drive));
        }
        const float fc = std::exp(lerpf(std::log(20000.f), std::log(2500.f), P(SOFT) * 0.01f));
        if (fc != lastFc_) { lastFc_ = fc; for (auto& f : soft_) f.setCutoff(fc, sr_); }
        const float tgt = comp * outG;
        float* ch[2] = {l, r};
        for (int c = 0; c < 2; ++c) {
            float* x = ch[c];
            for (int i = 0; i < n; ++i) {
                const float dry = x[i];
                const float v = dry * drive + (mode == 1 ? kBias : 0.f);
                float y;
                if (std::fabs(v - x1_[c]) > 1e-5f) y = (antiD(mode, v) - antiD(mode, x1_[c])) / (v - x1_[c]);
                else y = shaper(mode, 0.5f * (v + x1_[c]));
                x1_[c] = v;
                if (mode == 1) y -= shaper(1, kBias);
                y = dc_[c].process(y);
                y = soft_[c].process(y);
                const float g = gSm_[c].step(tgt, 0.995f);
                x[i] = dry * (1.f - mix) + y * g * mix;
            }
        }
    }
    void reset() override {
        x1_[0] = x1_[1] = 0.f;
        for (int c = 0; c < 2; ++c) { dc_[c].reset(); soft_[c].reset(); }
    }
protected:
    void prepare() override { for (auto& d : dc_) d.setCutoff(10.f, sr_); lastFc_ = -1.f; }
private:
    static constexpr float kBias = 0.25f;
    static float shaper(int mode, float x) {
        if (mode == 2) return x / (1.f + std::fabs(x));
        return std::tanh(x);
    }
    static float logcosh(float x) {
        const float a = std::fabs(x);
        return a + std::log1p(std::exp(-2.f * a)) - 0.69314718f;
    }
    static float antiD(int mode, float x) {
        if (mode == 2) { const float a = std::fabs(x); return a - std::log1p(a); }
        return logcosh(x);
    }
    float x1_[2] = {0, 0}, lastFc_ = -1.f;
    OnePoleHP dc_[2];
    OnePoleLP soft_[2];
    Smooth gSm_[2];
};

// ---------------------------------------------------------------------------
// 4.25 Tremolo dan 4.24 Auto Pan: LFO bersama
// ---------------------------------------------------------------------------
struct LfoBank {
    float phase = 0.f;
    float held = 0.f, smoothed = 0.f;  // untuk Random dan Smooth
    uint32_t seed = 12345u;
    void reset() { phase = 0.f; held = 0.f; smoothed = 0.f; }
    // Maju satu sampel; kembalikan nilai -1..1 pada offset fase 'off' (0..1).
    inline float next(float inc, int shape, float off, float smoothC) {
        phase += inc;
        if (phase >= 1.f) {
            phase -= 1.f;
            held = randBipolar(seed);
        }
        float p = phase + off;
        p -= std::floor(p);
        const float raw = shape == 5 ? held : lfoShape(shape, p);
        smoothed = raw + (smoothed - raw) * smoothC;
        return smoothed;
    }
};

inline constexpr ParamDesc kTremDesc[] = {
    C("Sync", "Free,Tempo", 1),       F("Rate", 0.05f, 30, 5, "Hz"), C("Note", GP_NOTE_CHOICES, 5),
    C("Shape", "Sine,Triangle,Square,Saw Up,Saw Down,Random", 0),
    F("Depth", 0, 100, 60, "%"),      F("Stereo", 0, 180, 0, "deg"), F("Phase", 0, 360, 0, "deg"),
    F("Smooth", 0, 100, 2, "ms"),
};

class Tremolo : public Effect {
public:
    enum { SYNC, RATE, NOTE, SHAPE, DEPTH, STEREO, PHASE, SMOOTH };
    Tremolo() : Effect(kTremDesc, 8) {}
    void process(float* l, float* r, int n, const FxContext& c) override {
        const float hz = PI(SYNC) == 1 ? std::max(20.f, c.bpm) / (60.f * noteBeats(PI(NOTE))) : P(RATE);
        const float inc = hz / sr_, depth = P(DEPTH) * 0.01f;
        const int shape = PI(SHAPE);
        const float offR = (P(PHASE) + P(STEREO)) / 360.f, offL = P(PHASE) / 360.f;
        const float sm = timeCoef(P(SMOOTH), sr_);
        for (int i = 0; i < n; ++i) {
            const float a = lfo_[0].next(inc, shape, offL, sm);
            const float b = lfo_[1].next(inc, shape, offR, sm);
            l[i] *= 1.f - depth * (0.5f - 0.5f * a);
            r[i] *= 1.f - depth * (0.5f - 0.5f * b);
        }
    }
    void reset() override { lfo_[0].reset(); lfo_[1].reset(); }
private:
    LfoBank lfo_[2];
};

inline constexpr ParamDesc kAPanDesc[] = {
    C("Sync", "Free,Tempo", 1), F("Rate", 0.05f, 20, 1, "Hz"), C("Note", GP_NOTE_CHOICES, 4),
    C("Shape", "Sine,Triangle,Square,Saw Up,Saw Down,Random", 0),
    F("Depth", 0, 100, 100, "%"), F("Phase", 0, 360, 0, "deg"), F("Smooth", 0, 100, 8, "ms"),
    F("Centre", -100, 100, 0, "%"),
};

class AutoPan : public Effect {
public:
    enum { SYNC, RATE, NOTE, SHAPE, DEPTH, PHASE, SMOOTH, CENTRE };
    AutoPan() : Effect(kAPanDesc, 8) {}
    void process(float* l, float* r, int n, const FxContext& c) override {
        const float hz = PI(SYNC) == 1 ? std::max(20.f, c.bpm) / (60.f * noteBeats(PI(NOTE))) : P(RATE);
        const float inc = hz / sr_, depth = P(DEPTH) * 0.01f, centre = P(CENTRE) * 0.01f;
        const int shape = PI(SHAPE);
        const float off = P(PHASE) / 360.f, sm = timeCoef(P(SMOOTH), sr_);
        for (int i = 0; i < n; ++i) {
            const float pos = clampf(centre + depth * lfo_.next(inc, shape, off, sm), -1.f, 1.f);
            const float th = (pos + 1.f) * 0.25f * kPi;
            l[i] *= std::cos(th) * 1.41421356f;
            r[i] *= std::sin(th) * 1.41421356f;
        }
    }
    void reset() override { lfo_.reset(); }
private:
    LfoBank lfo_;
};

// ---------------------------------------------------------------------------
// 4.35 Utility
// ---------------------------------------------------------------------------
inline constexpr ParamDesc kUtilDesc[] = {
    F("Gain", -60, 24, 0, "dB"),  F("Pan", -100, 100, 0, "%"), F("Width", 0, 200, 100, "%"),
    T("Polarity L", false),       T("Polarity R", false),      T("Swap L/R", false),
    T("Mono", false),             T("Mute L", false),          T("Mute R", false),
    T("DC Filter", false),        C("Mode", "Stereo,Mid solo,Side solo,Left only,Right only", 0),
};

class Utility : public Effect {
public:
    enum { GAIN, PAN, WIDTH, POLL, POLR, SWAP, MONO, MUTEL, MUTER, DC, MODE };
    Utility() : Effect(kUtilDesc, 11) {}
    void process(float* l, float* r, int n, const FxContext&) override {
        const float gain = dbToLin(P(GAIN)), pan = P(PAN) * 0.01f, width = P(WIDTH) * 0.01f;
        const float pl = PB(POLL) ? -1.f : 1.f, pr = PB(POLR) ? -1.f : 1.f;
        const bool swap = PB(SWAP), mono = PB(MONO), muteL = PB(MUTEL), muteR = PB(MUTER), dc = PB(DC);
        const int mode = PI(MODE);
        // Pan sungguhan: sisi yang dibuang berpindah ke sisi lain (daya terjaga).
        const float ang = std::fabs(pan) * 0.5f * kPi;
        const float keep = std::cos(ang), move = std::sin(ang);
        for (int i = 0; i < n; ++i) {
            float a = l[i] * pl, b = r[i] * pr;
            if (dc) { a = dcL_.process(a); b = dcR_.process(b); }
            if (swap) std::swap(a, b);
            if (mode == 3) b = a;
            else if (mode == 4) a = b;
            else if (mode != 0) {
                const float m = 0.5f * (a + b), s = 0.5f * (a - b);
                if (mode == 1) { a = b = m; } else { a = s; b = -s; }
            }
            if (width != 1.f) {
                const float m = 0.5f * (a + b), s = 0.5f * (a - b) * width;
                a = m + s; b = m - s;
            }
            if (mono) { a = b = 0.5f * (a + b); }
            if (pan > 0.f) { b += a * move; a *= keep; }
            else if (pan < 0.f) { a += b * move; b *= keep; }
            if (muteL) a = 0.f;
            if (muteR) b = 0.f;
            const float g = gSm_.step(gain, 0.995f);
            l[i] = a * g;
            r[i] = b * g;
        }
    }
    void reset() override { dcL_.reset(); dcR_.reset(); }
protected:
    void prepare() override { dcL_.setCutoff(10.f, sr_); dcR_.setCutoff(10.f, sr_); }
private:
    OnePoleHP dcL_, dcR_;
    Smooth gSm_;
};

// ---------------------------------------------------------------------------
// 4.35 Hum Remover (notch 50/60 Hz + harmonik, mode Auto)
// ---------------------------------------------------------------------------
inline constexpr ParamDesc kHumDesc[] = {
    C("Mains", "50 Hz,60 Hz,Auto", 2), F("Harmonics", 1, 8, 3), F("Q", 5, 100, 30), F("Depth", 0, 100, 100, "%"),
};

class HumRemover : public Effect {
public:
    enum { MAINS, HARM, Q, DEPTH };
    HumRemover() : Effect(kHumDesc, 4) {}
    float detectedHz() const { return detected_.load(std::memory_order_relaxed); }

    void process(float* l, float* r, int n, const FxContext&) override {
        const int mains = PI(MAINS);
        const int nh = clampi((int)(P(HARM) + 0.5f), 1, 8);
        const float depth = P(DEPTH) * 0.01f;
        if (mains == 2) autoDetect(l, r, n);
        const float f0 = mains == 0 ? 50.f : (mains == 1 ? 60.f : (autoHz_ > 0.f ? autoHz_ : 50.f));
        if (f0 != lastF_ || P(Q) != lastQ_ || nh != lastN_) {
            lastF_ = f0; lastQ_ = P(Q); lastN_ = nh;
            for (int h = 0; h < 8; ++h)
                for (int c = 0; c < 2; ++c) {
                    const double f = f0 * (h + 1);
                    if (h < nh && f < sr_ * 0.45) notch_[h][c].notch(sr_, f, lastQ_);
                    else notch_[h][c] = Biquad();
                }
        }
        for (int i = 0; i < n; ++i) {
            float a = l[i], b = r[i];
            for (int h = 0; h < nh; ++h) { a = notch_[h][0].process(a); b = notch_[h][1].process(b); }
            l[i] = l[i] + (a - l[i]) * depth;
            r[i] = r[i] + (b - r[i]) * depth;
        }
    }
    void reset() override {
        for (auto& h : notch_) for (auto& b : h) b.reset();
        for (auto& g : gz_) g = {};
        gN_ = 0;
    }
protected:
    void prepare() override { lastF_ = -1.f; winLen_ = (int)(0.5f * sr_); }
private:
    // Goertzel: energi pada 50/100/150 vs 60/120/180 Hz, jendela 0,5 detik.
    struct Gz { double s1 = 0, s2 = 0; };
    void autoDetect(const float* l, const float* r, int n) {
        static const double kF[6] = {50, 100, 150, 60, 120, 180};
        double coef[6];
        for (int k = 0; k < 6; ++k) coef[k] = 2.0 * std::cos(2.0 * 3.14159265358979323846 * kF[k] / sr_);
        for (int i = 0; i < n; ++i) {
            const double x = 0.5 * (l[i] + r[i]);
            for (int k = 0; k < 6; ++k) {
                const double s0 = x + coef[k] * gz_[k].s1 - gz_[k].s2;
                gz_[k].s2 = gz_[k].s1;
                gz_[k].s1 = s0;
            }
            if (++gN_ >= winLen_) {
                double e[6];
                for (int k = 0; k < 6; ++k) {
                    e[k] = gz_[k].s1 * gz_[k].s1 + gz_[k].s2 * gz_[k].s2 - coef[k] * gz_[k].s1 * gz_[k].s2;
                    gz_[k] = {};
                }
                gN_ = 0;
                const double e50 = e[0] + e[1] + e[2], e60 = e[3] + e[4] + e[5];
                if (e50 > e60 * 1.5) autoHz_ = 50.f;
                else if (e60 > e50 * 1.5) autoHz_ = 60.f;
                detected_.store(autoHz_, std::memory_order_relaxed);
            }
        }
    }
    Biquad notch_[8][2];
    Gz gz_[6];
    int gN_ = 0, winLen_ = 24000, lastN_ = -1;
    float autoHz_ = 50.f, lastF_ = -1.f, lastQ_ = -1.f;
    std::atomic<float> detected_{0.f};
};

// ---------------------------------------------------------------------------
// 4.36 Bitcrusher
// ---------------------------------------------------------------------------
inline constexpr ParamDesc kCrushDesc[] = {
    F("Bits", 1, 16, 8), F("Downsample", 1, 32, 1, "x"), F("Dither", 0, 100, 0, "%"), F("Mix", 0, 100, 100, "%"),
};

class Bitcrusher : public Effect {
public:
    enum { BITS, DOWN, DITHER, MIX };
    Bitcrusher() : Effect(kCrushDesc, 4) {}
    void process(float* l, float* r, int n, const FxContext&) override {
        const float bits = std::floor(P(BITS) + 0.5f);
        const float levels = std::pow(2.f, bits - 1.f);
        const int down = clampi((int)(P(DOWN) + 0.5f), 1, 32);
        const float dith = P(DITHER) * 0.01f / levels, mix = P(MIX) * 0.01f;
        for (int i = 0; i < n; ++i) {
            if (cnt_ == 0) {
                held_[0] = quant(l[i], levels, dith);
                held_[1] = quant(r[i], levels, dith);
            }
            if (++cnt_ >= down) cnt_ = 0;
            l[i] = l[i] * (1.f - mix) + held_[0] * mix;
            r[i] = r[i] * (1.f - mix) + held_[1] * mix;
        }
    }
    void reset() override { cnt_ = 0; held_[0] = held_[1] = 0.f; }
private:
    float quant(float x, float levels, float dith) {
        const float d = dith > 0.f ? (randBipolar(seed_) - randBipolar(seed_)) * 0.5f * dith : 0.f;
        return std::round(clampf(x + d, -1.f, 1.f) * levels) / levels;
    }
    int cnt_ = 0;
    float held_[2] = {0, 0};
    uint32_t seed_ = 777u;
};

// ---------------------------------------------------------------------------
// 4.32 Test Tone
// ---------------------------------------------------------------------------
inline constexpr ParamDesc kToneDesc[] = {
    C("Wave", "Sine,Square,Triangle,Saw,White,Pink,Sweep", 0), F("Freq", 20, 20000, 1000, "Hz"),
    F("Level", -60, 0, -30, "dB"), C("Channels", "Stereo,Left,Right", 0),
    C("Mode", "Replace,Add", 0),   F("Sweep", 2, 60, 10, "s"),
};

class TestTone : public Effect {
public:
    enum { WAVE, FREQ, LEVEL, CHANNELS, MODE, SWEEP };
    TestTone() : Effect(kToneDesc, 6) {}
    void process(float* l, float* r, int n, const FxContext&) override {
        const int wave = PI(WAVE), chans = PI(CHANNELS);
        const bool add = PI(MODE) == 1;
        const float lvl = dbToLin(P(LEVEL));
        float f = P(FREQ);
        const float sweepT = P(SWEEP);
        for (int i = 0; i < n; ++i) {
            if (wave == 6) {
                sweepPos_ += 1.f / (sweepT * sr_);
                if (sweepPos_ >= 1.f) sweepPos_ -= 1.f;
                f = 20.f * std::pow(1000.f, sweepPos_);
            }
            phase_ += f / sr_;
            phase_ -= std::floor(phase_);
            float s;
            switch (wave) {
                case 0: case 6: s = std::sin(kTwoPi * phase_); break;
                case 1: s = phase_ < 0.5f ? 1.f : -1.f; break;
                case 2: s = lfoShape(1, phase_); break;
                case 3: s = 2.f * phase_ - 1.f; break;
                case 4: s = randBipolar(seed_); break;
                default: s = pink(); break;
            }
            s *= lvl;
            const float tl = chans == 2 ? 0.f : s, tr = chans == 1 ? 0.f : s;
            if (add) { l[i] += tl; r[i] += tr; } else { l[i] = tl; r[i] = tr; }
        }
    }
    void reset() override { phase_ = 0.f; sweepPos_ = 0.f; for (auto& v : b_) v = 0.f; }
private:
    float pink() {  // filter pink noise Paul Kellet (ekonomis)
        const float w = randBipolar(seed_);
        b_[0] = 0.99886f * b_[0] + w * 0.0555179f;
        b_[1] = 0.99332f * b_[1] + w * 0.0750759f;
        b_[2] = 0.96900f * b_[2] + w * 0.1538520f;
        b_[3] = 0.86650f * b_[3] + w * 0.3104856f;
        b_[4] = 0.55000f * b_[4] + w * 0.5329522f;
        b_[5] = -0.7616f * b_[5] - w * 0.0168980f;
        const float out = b_[0] + b_[1] + b_[2] + b_[3] + b_[4] + b_[5] + b_[6] + w * 0.5362f;
        b_[6] = w * 0.115926f;
        return out * 0.11f;
    }
    float phase_ = 0.f, sweepPos_ = 0.f, b_[7] = {};
    uint32_t seed_ = 4242u;
};

}  // namespace gp
