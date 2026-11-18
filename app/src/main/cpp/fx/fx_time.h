// GP Mix (Android) - efek waktu & ruang: Delay, Reverb, Chorus, Transposer (bab 4.15, 4.21-4.23, 4.27).
#pragma once
#include "fx_core.h"

namespace gp {

// ---------------------------------------------------------------------------
// 4.23 Delay
// ---------------------------------------------------------------------------
inline constexpr ParamDesc kDelayDesc[] = {
    C("Mode", "Ping-Pong,Stereo,Mono", 0), C("Sync", "Free,Tempo", 0),
    F("Time", 1, 2000, 375, "ms"),         C("Note", GP_NOTE_CHOICES, 4),
    F("R Ratio", 25, 200, 100, "%"),       F("Feedback", 0, 95, 35, "%"),
    F("Lo Cut", 20, 1000, 80, "Hz"),       F("Hi Cut", 1000, 20000, 7000, "Hz"),
    F("Mix", 0, 100, 30, "%"),
};

class Delay : public Effect {
public:
    enum { MODE, SYNC, TIME, NOTE, RATIO, FB, LOCUT, HICUT, MIX };
    Delay() : Effect(kDelayDesc, 9) {}
    void process(float* l, float* r, int n, const FxContext& c) override {
        const int mode = PI(MODE);
        float tMs = PI(SYNC) == 1 ? noteBeats(PI(NOTE)) * 60000.f / std::max(20.f, c.bpm) : P(TIME);
        tMs = clampf(tMs, 1.f, 2000.f);
        const float maxD = (float)maxSamples_ - 4.f;
        const float tl = clampf(tMs * 0.001f * sr_, 2.f, maxD);
        const float tr = clampf(tl * P(RATIO) * 0.01f, 2.f, maxD);
        const float fb = P(FB) * 0.01f, mix = P(MIX) * 0.01f;
        if (P(LOCUT) != lastLo_ || P(HICUT) != lastHi_) {
            lastLo_ = P(LOCUT); lastHi_ = P(HICUT);
            for (int ch = 0; ch < 2; ++ch) { hp_[ch].setCutoff(lastLo_, sr_); lp_[ch].setCutoff(lastHi_, sr_); }
        }
        if (!init_) { curL_ = tl; curR_ = tr; init_ = true; }
        const float slew = 1.f - std::exp(-1.f / (0.05f * sr_));
        for (int i = 0; i < n; ++i) {
            curL_ += (tl - curL_) * slew;
            curR_ += (tr - curR_) * slew;
            const float dl = l[i], dr = r[i];
            float wl, wr;
            if (mode == 1) {  // Stereo
                wl = dl_[0].read(curL_ - 1.f);
                wr = dl_[1].read(curR_ - 1.f);
                dl_[0].write(dl + fbf(0, wl) * fb);
                dl_[1].write(dr + fbf(1, wr) * fb);
            } else if (mode == 2) {  // Mono
                const float in = 0.5f * (dl + dr);
                wl = dl_[0].read(curL_ - 1.f);
                wr = wl;
                dl_[0].write(in + fbf(0, wl) * fb);
            } else {  // Ping-Pong
                const float in = 0.5f * (dl + dr);
                wl = dl_[0].read(curL_ - 1.f);
                wr = dl_[1].read(curR_ - 1.f);
                dl_[0].write(in + fbf(1, wr) * fb);
                dl_[1].write(wl);
            }
            l[i] = dl * (1.f - mix) + wl * mix;
            r[i] = dr * (1.f - mix) + wr * mix;
        }
    }
    void reset() override {
        dl_[0].clear(); dl_[1].clear();
        for (int ch = 0; ch < 2; ++ch) { hp_[ch].reset(); lp_[ch].reset(); }
        init_ = false;
    }
protected:
    void prepare() override {
        maxSamples_ = (int)(4.2f * sr_);
        dl_[0].init(maxSamples_);
        dl_[1].init(maxSamples_);
        lastLo_ = lastHi_ = -1.f;
    }
private:
    inline float fbf(int ch, float x) { return hp_[ch].process(lp_[ch].process(x)); }
    DelayLine dl_[2];
    OnePoleHP hp_[2];
    OnePoleLP lp_[2];
    float curL_ = 0, curR_ = 0, lastLo_ = -1, lastHi_ = -1;
    bool init_ = false;
    int maxSamples_ = 200000;
};

// ---------------------------------------------------------------------------
// 4.27 Reverb: FDN 8 jalur (matriks Hadamard), diffuser input, damping, modulasi.
// ---------------------------------------------------------------------------
inline constexpr ParamDesc kReverbDesc[] = {
    C("Type", "Room,Hall,Plate", 0),    F("Pre-Delay", 0, 200, 20, "ms"), F("Size", 0, 100, 50, "%"),
    F("Decay", 0.1f, 20, 2.2f, "s"),    F("Damping", 0, 100, 40, "%"),    F("Diffusion", 0, 100, 70, "%"),
    F("Modulation", 0, 100, 30, "%"),   F("Width", 0, 100, 100, "%"),     F("Lo Cut", 20, 1000, 100, "Hz"),
    F("Hi Cut", 1000, 20000, 12000, "Hz"), F("Mix", 0, 100, 25, "%"),
};

class Reverb : public Effect {
public:
    enum { TYPE, PRE, SIZE, DECAY, DAMP, DIFF, MOD, WIDTH, LOCUT, HICUT, MIX };
    static constexpr int N = 8;
    Reverb() : Effect(kReverbDesc, 11) {}

    void process(float* l, float* r, int n, const FxContext&) override {
        static const float kBase[3][N] = {
            {7.3f, 9.1f, 11.3f, 13.7f, 16.9f, 19.7f, 23.3f, 27.1f},      // Room
            {23.3f, 29.5f, 34.1f, 41.3f, 47.9f, 53.9f, 61.1f, 69.7f},    // Hall
            {11.1f, 14.9f, 19.7f, 24.3f, 29.1f, 33.7f, 38.9f, 43.3f}};   // Plate
        static const float kRate[N] = {0.11f, 0.17f, 0.23f, 0.29f, 0.13f, 0.19f, 0.31f, 0.37f};
        static const float kSL[N] = {1, -1, 1, -1, 1, -1, 1, -1};
        static const float kSR[N] = {1, 1, -1, -1, 1, 1, -1, -1};
        const int type = clampi(PI(TYPE), 0, 2);
        const float scale = 0.5f + 1.5f * P(SIZE) * 0.01f;
        const float decay = P(DECAY);
        const float damp = P(DAMP) * 0.01f * 0.9f;
        const float apG = 0.3f + 0.45f * P(DIFF) * 0.01f;
        const float modDepth = P(MOD) * 0.01f * 0.0006f * sr_;  // sampel (<= 0,6 ms)
        const float width = P(WIDTH) * 0.01f, mix = P(MIX) * 0.01f;
        const float preS = clampf(P(PRE) * 0.001f * sr_, 0.f, (float)preMax_ - 2.f);
        if (P(LOCUT) != lastLo_ || P(HICUT) != lastHi_) {
            lastLo_ = P(LOCUT); lastHi_ = P(HICUT);
            for (int ch = 0; ch < 2; ++ch) { hp_[ch].setCutoff(lastLo_, sr_); lp_[ch].setCutoff(lastHi_, sr_); }
        }
        float tgt[N], g[N];
        for (int i = 0; i < N; ++i) {
            tgt[i] = clampf(kBase[type][i] * scale * 0.001f * sr_, 4.f, (float)lineMax_ - 4.f - modDepth);
            g[i] = std::pow(10.f, -3.f * (tgt[i] / sr_) / decay);
            if (!init_) len_[i] = tgt[i];
        }
        init_ = true;
        const float lenSlew = 0.0008f;
        for (int i = 0; i < n; ++i) {
            const float dryL = l[i], dryR = r[i];
            pre_.write(0.5f * (dryL + dryR));
            float x = pre_.read(std::max(0.f, preS - 1.f));
            for (int k = 0; k < 4; ++k) {  // diffuser (all-pass seri)
                float& z = apState_[k][apIdx_[k]];
                const float v = x - apG * z;
                const float y = apG * v + z;
                z = v;
                if (++apIdx_[k] >= apLen_[k]) apIdx_[k] = 0;
                x = y;
            }
            float d[N], v[N];
            for (int k = 0; k < N; ++k) {
                len_[k] += (tgt[k] - len_[k]) * lenSlew;
                phase_[k] += kRate[k] / sr_;
                if (phase_[k] >= 1.f) phase_[k] -= 1.f;
                const float m = modDepth * std::sin(kTwoPi * phase_[k]);
                const float y = line_[k].read(std::max(0.f, len_[k] + m - 1.f));
                d[k] = damp_[k].process2(y, damp);
                v[k] = d[k] * g[k];
            }
            for (int len = 1; len < N; len <<= 1)  // Hadamard cepat
                for (int a = 0; a < N; a += len * 2)
                    for (int b = 0; b < len; ++b) {
                        const float p = v[a + b], q = v[a + b + len];
                        v[a + b] = p + q;
                        v[a + b + len] = p - q;
                    }
            float wl = 0.f, wr = 0.f;
            for (int k = 0; k < N; ++k) {
                line_[k].write(x * 0.5f + v[k] * 0.35355339f);
                wl += kSL[k] * d[k];
                wr += kSR[k] * d[k];
            }
            wl *= 0.5f;
            wr *= 0.5f;
            const float mid = 0.5f * (wl + wr), side = 0.5f * (wl - wr) * width;
            wl = lp_[0].process(hp_[0].process(mid + side));
            wr = lp_[1].process(hp_[1].process(mid - side));
            l[i] = dryL * (1.f - mix) + wl * mix;
            r[i] = dryR * (1.f - mix) + wr * mix;
        }
    }
    void reset() override {
        for (auto& d : line_) d.clear();
        pre_.clear();
        for (auto& a : apState_) std::fill(a.begin(), a.end(), 0.f);
        for (int k = 0; k < 4; ++k) apIdx_[k] = 0;
        for (auto& f : damp_) f.z = 0.f;
        for (int ch = 0; ch < 2; ++ch) { hp_[ch].reset(); lp_[ch].reset(); }
        init_ = false;
        for (int k = 0; k < N; ++k) phase_[k] = (float)k / (float)N;
    }
protected:
    void prepare() override {
        lineMax_ = (int)(0.25f * sr_);
        preMax_ = (int)(0.21f * sr_);
        for (auto& d : line_) d.init(lineMax_);
        pre_.init(preMax_);
        static const float kAp[4] = {4.7f, 7.9f, 11.3f, 14.9f};
        for (int k = 0; k < 4; ++k) {
            apLen_[k] = std::max(2, (int)(kAp[k] * 0.001f * sr_));
            apState_[k].assign(apLen_[k], 0.f);
            apIdx_[k] = 0;
        }
        lastLo_ = lastHi_ = -1.f;
    }
private:
    struct Damp {  // one-pole LP dengan koefisien berubah-ubah
        float z = 0.f;
        inline float process2(float x, float a) { z = (1.f - a) * x + a * z; return z; }
    };
    DelayLine line_[N], pre_;
    Damp damp_[N];
    std::vector<float> apState_[4];
    int apLen_[4] = {225, 379, 542, 715}, apIdx_[4] = {0, 0, 0, 0};
    float len_[N] = {}, phase_[N] = {0, 0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f};
    OnePoleHP hp_[2];
    OnePoleLP lp_[2];
    float lastLo_ = -1, lastHi_ = -1;
    bool init_ = false;
    int lineMax_ = 12000, preMax_ = 10080;
};

// ---------------------------------------------------------------------------
// 4.15 Chorus
// ---------------------------------------------------------------------------
inline constexpr ParamDesc kChorusDesc[] = {
    F("Rate", 0.05f, 10, 0.6f, "Hz"), F("Depth", 0, 100, 50, "%"),   F("Voices", 1, 4, 3),
    F("Delay", 3, 40, 14, "ms"),      F("Feedback", 0, 60, 0, "%"),  F("Spread", 0, 100, 100, "%"),
    F("Hi Cut", 1000, 20000, 14000, "Hz"), F("Mix", 0, 100, 50, "%"),
};

class Chorus : public Effect {
public:
    enum { RATE, DEPTH, VOICES, DELAY, FB, SPREAD, HICUT, MIX };
    Chorus() : Effect(kChorusDesc, 8) {}
    void process(float* l, float* r, int n, const FxContext&) override {
        const int nv = clampi((int)(P(VOICES) + 0.5f), 1, 4);
        const float baseMs = P(DELAY), amp = P(DEPTH) * 0.01f * 0.5f * baseMs;
        const float fb = P(FB) * 0.01f, spread = P(SPREAD) * 0.01f, mix = P(MIX) * 0.01f;
        const float inc = P(RATE) / sr_;
        if (P(HICUT) != lastHi_) { lastHi_ = P(HICUT); lp_[0].setCutoff(lastHi_, sr_); lp_[1].setCutoff(lastHi_, sr_); }
        float gl[4], gr[4];
        for (int v = 0; v < nv; ++v) {
            const float pos = nv > 1 ? spread * (-1.f + 2.f * (float)v / (float)(nv - 1)) : 0.f;
            const float th = (pos + 1.f) * 0.25f * kPi;
            gl[v] = std::cos(th) * 1.41421356f;
            gr[v] = std::sin(th) * 1.41421356f;
        }
        const float norm = 1.f / std::sqrt((float)nv);
        for (int i = 0; i < n; ++i) {
            phase_ += inc;
            if (phase_ >= 1.f) phase_ -= 1.f;
            const float in = 0.5f * (l[i] + r[i]);
            float wl = 0.f, wr = 0.f, sum = 0.f;
            for (int v = 0; v < nv; ++v) {
                float p = phase_ + (float)v / (float)nv;
                p -= std::floor(p);
                const float dMs = baseMs + amp * std::sin(kTwoPi * p);
                const float tap = dl_.read(std::max(0.f, dMs * 0.001f * sr_ - 1.f));
                sum += tap;
                wl += tap * gl[v];
                wr += tap * gr[v];
            }
            dl_.write(in + fb * sum * norm);
            wl = lp_[0].process(wl * norm);
            wr = lp_[1].process(wr * norm);
            l[i] = l[i] * (1.f - mix) + wl * mix;
            r[i] = r[i] * (1.f - mix) + wr * mix;
        }
    }
    void reset() override { dl_.clear(); lp_[0].reset(); lp_[1].reset(); }
protected:
    void prepare() override { dl_.init((int)(0.07f * sr_)); lastHi_ = -1.f; }  // maks 40 ms * 1,5 = 60 ms
private:
    DelayLine dl_;
    OnePoleLP lp_[2];
    float phase_ = 0.f, lastHi_ = -1.f;
};

// ---------------------------------------------------------------------------
// 4.22 Transposer (pitch shift real-time, dua tap berjendela)
// ---------------------------------------------------------------------------
inline constexpr ParamDesc kTransDesc[] = {
    F("Transpose", -12, 12, 0, "st"), F("Fine", -50, 50, 0, "cent"),
    F("Window", 20, 100, 50, "ms"),   F("Mix", 0, 100, 100, "%"),
};

class Transposer : public Effect {
public:
    enum { SEMI, FINE, WIN, MIX };
    Transposer() : Effect(kTransDesc, 4) {}
    void process(float* l, float* r, int n, const FxContext&) override {
        const float W = std::max(16.f, P(WIN) * 0.001f * sr_);
        const float semis = P(SEMI) + P(FINE) * 0.01f;
        const float ratio = std::pow(2.f, semis / 12.f);
        const float dphi = std::fabs(semis) < 1e-4f ? 0.f : (1.f - ratio) / W;
        const float mix = P(MIX) * 0.01f;
        const int half = (int)(W * 0.5f);
        setLatency(half + 1);
        if (dphi == 0.f) phase_ = 0.f;
        for (int i = 0; i < n; ++i) {
            dl_[0].write(l[i]);
            dl_[1].write(r[i]);
            phase_ += dphi;
            phase_ -= std::floor(phase_);
            const float p2 = phase_ + 0.5f - std::floor(phase_ + 0.5f);
            const float d1 = phase_ * W, d2 = p2 * W;
            const float s1 = std::sin(kPi * phase_), s2 = std::sin(kPi * p2);
            const float g1 = s1 * s1, g2 = s2 * s2;
            const float wl = g1 * dl_[0].read(d1) + g2 * dl_[0].read(d2);
            const float wr = g1 * dl_[1].read(d1) + g2 * dl_[1].read(d2);
            const float dryL = dl_[0].readInt(half), dryR = dl_[1].readInt(half);
            l[i] = dryL * (1.f - mix) + wl * mix;
            r[i] = dryR * (1.f - mix) + wr * mix;
        }
    }
    void reset() override { dl_[0].clear(); dl_[1].clear(); phase_ = 0.f; }
protected:
    void prepare() override { dl_[0].init((int)(0.12f * sr_)); dl_[1].init((int)(0.12f * sr_)); }
private:
    DelayLine dl_[2];
    float phase_ = 0.f;
};

}  // namespace gp
