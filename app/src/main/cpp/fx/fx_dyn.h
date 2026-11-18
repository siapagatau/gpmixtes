// GP Mix (Android) - efek dinamika: Gate, Expander, Compressor, De-esser, Limiter, Ducking.
// Nama, rentang dan default parameter mengikuti manual GP Mix (bab 4.6 - 4.14).
#pragma once
#include "fx_core.h"

namespace gp {

// Filter jalur key (side chain): high-pass dan low-pass; dimatikan di nilai ekstrem.
struct KeyPath {
    Biquad hp, lp;
    bool hpOn = false, lpOn = false;
    float lastHp = -1.f, lastLp = -1.f;
    void update(float sr, float hpHz, float lpHz) {
        if (hpHz != lastHp) {
            lastHp = hpHz;
            hpOn = hpHz > 20.5f;
            if (hpOn) hp.highpass(sr, hpHz, 0.7071);
        }
        if (lpHz != lastLp) {
            lastLp = lpHz;
            lpOn = lpHz < 19999.f;
            if (lpOn) lp.lowpass(sr, lpHz, 0.7071);
        }
    }
    inline float process(float x) {
        if (hpOn) x = hp.process(x);
        if (lpOn) x = lp.process(x);
        return x;
    }
    void reset() { hp.reset(); lp.reset(); }
};

// Penghalus gain reduction (dB, >=0) dengan attack / release / hold.
struct GrSmoother {
    float gr = 0.f;
    int hold = 0;
    // target >= 0 (dB atenuasi). Atenuasi naik = release (menutup); turun = attack (membuka).
    inline float step(float target, float aC, float rC, int holdSamples) {
        if (target <= 0.01f) {
            hold = holdSamples;
        }
        if (target > gr) {
            if (hold > 0 && target > gr) { --hold; return gr; }
            gr = target + (gr - target) * rC;
        } else {
            gr = target + (gr - target) * aC;
        }
        return gr;
    }
    void reset() { gr = 0.f; hold = 0; }
};

// ---------------------------------------------------------------------------
// 4.6 Gate
// ---------------------------------------------------------------------------
inline constexpr ParamDesc kGateDesc[] = {
    F("Threshold", -80, 0, -40, "dB"),   F("Range", -80, 0, -80, "dB"),
    F("Hyst", 0, 12, 4, "dB"),           F("Attack", 0.05f, 50, 1, "ms"),
    F("Hold", 0, 500, 50, "ms"),         F("Release", 5, 2000, 150, "ms"),
    F("Lookahead", 0, 10, 1, "ms"),      F("Key HP", 20, 2000, 20, "Hz"),
    F("Key LP", 1000, 20000, 20000, "Hz"), T("Key Listen", false),
};

class Gate : public Effect {
public:
    enum { THR, RANGE, HYST, ATT, HOLD, REL, LOOK, KHP, KLP, LISTEN };
    Gate() : Effect(kGateDesc, 10) {}
    void process(float* l, float* r, int n, const FxContext& c) override {
        const float thr = P(THR), hyst = P(HYST), rangeLin = dbToLin(P(RANGE));
        const float aC = timeCoef(P(ATT), sr_), rC = timeCoef(P(REL), sr_);
        const int hold = (int)(P(HOLD) * 0.001f * sr_);
        const int la = clampi((int)(P(LOOK) * 0.001f * sr_ + 0.5f), 0, maxLa_);
        const bool listen = PB(LISTEN);
        const float detRel = timeCoef(10.f, sr_);
        setLatency(la);
        key_.update(sr_, P(KHP), P(KLP));
        for (int i = 0; i < n; ++i) {
            float k = c.keyL ? 0.5f * (c.keyL[i] + (c.keyR ? c.keyR[i] : c.keyL[i])) : 0.5f * (l[i] + r[i]);
            k = key_.process(k);
            const float a = std::fabs(k);
            env_ = a > env_ ? a : a + (env_ - a) * detRel;
            const float db = linToDb(env_);
            if (!open_) {
                if (db > thr) { open_ = true; holdCnt_ = hold; }
            } else if (db > thr - hyst) {
                holdCnt_ = hold;
            } else if (holdCnt_ > 0) {
                --holdCnt_;
            } else {
                open_ = false;
            }
            const float target = open_ ? 1.f : rangeLin;
            gain_ = target + (gain_ - target) * (target > gain_ ? aC : rC);
            dl_.write(l[i]);
            dr_.write(r[i]);
            if (listen) { l[i] = r[i] = k; }
            else { l[i] = dl_.readInt(la) * gain_; r[i] = dr_.readInt(la) * gain_; }
        }
        setGr(-linToDb(gain_));
    }
    void reset() override { dl_.clear(); dr_.clear(); key_.reset(); env_ = 0; gain_ = 0; open_ = false; holdCnt_ = 0; }
protected:
    void prepare() override {
        maxLa_ = (int)(0.011f * sr_);
        dl_.init(maxLa_ + 2);
        dr_.init(maxLa_ + 2);
        key_.lastHp = key_.lastLp = -1.f;
    }
private:
    DelayLine dl_, dr_;
    KeyPath key_;
    float env_ = 0, gain_ = 0;
    bool open_ = false;
    int holdCnt_ = 0, maxLa_ = 528;
};

// ---------------------------------------------------------------------------
// 4.7 Expander (downward)
// ---------------------------------------------------------------------------
inline constexpr ParamDesc kExpDesc[] = {
    F("Threshold", -80, 0, -45, "dB"), F("Ratio", 1, 20, 3, ":1"), F("Range", 0, 80, 40, "dB"),
    F("Knee", 0, 24, 6, "dB"),         F("Attack", 0.05f, 100, 1, "ms"), F("Hold", 0, 500, 30, "ms"),
    F("Release", 5, 2000, 150, "ms"),  F("Lookahead", 0, 10, 1, "ms"),
    C("Detector", "Peak,RMS", 1),      F("Link", 0, 100, 100, "%"), F("Mix", 0, 100, 100, "%"),
    F("Key HP", 20, 2000, 20, "Hz"),   F("Key LP", 1000, 20000, 20000, "Hz"), T("Key Listen", false),
};

class Expander : public Effect {
public:
    enum { THR, RATIO, RANGE, KNEE, ATT, HOLD, REL, LOOK, DET, LINK, MIX, KHP, KLP, LISTEN };
    Expander() : Effect(kExpDesc, 14) {}
    void process(float* l, float* r, int n, const FxContext& c) override {
        const float thr = P(THR), ratio = P(RATIO), range = P(RANGE), knee = P(KNEE);
        const float aC = timeCoef(P(ATT), sr_), rC = timeCoef(P(REL), sr_);
        const int hold = (int)(P(HOLD) * 0.001f * sr_);
        const int la = clampi((int)(P(LOOK) * 0.001f * sr_ + 0.5f), 0, maxLa_);
        const bool rms = PI(DET) == 1, listen = PB(LISTEN);
        const float link = P(LINK) * 0.01f, mix = P(MIX) * 0.01f;
        const float peakRel = timeCoef(10.f, sr_), rmsC = timeCoef(20.f, sr_);
        setLatency(la);
        keyL_.update(sr_, P(KHP), P(KLP));
        keyR_.update(sr_, P(KHP), P(KLP));
        float grMax = 0.f;
        for (int i = 0; i < n; ++i) {
            float kl = c.keyL ? c.keyL[i] : l[i];
            float kr = c.keyR ? c.keyR[i] : r[i];
            kl = keyL_.process(kl);
            kr = keyR_.process(kr);
            float db[2];
            const float in[2] = {kl, kr};
            for (int ch = 0; ch < 2; ++ch) {
                const float a = std::fabs(in[ch]);
                float lvl;
                if (rms) { ms_[ch] = in[ch] * in[ch] + (ms_[ch] - in[ch] * in[ch]) * rmsC; lvl = std::sqrt(ms_[ch]); }
                else { env_[ch] = a > env_[ch] ? a : a + (env_[ch] - a) * peakRel; lvl = env_[ch]; }
                db[ch] = linToDb(lvl);
            }
            const float linked = std::max(db[0], db[1]);
            float g[2];
            for (int ch = 0; ch < 2; ++ch) {
                const float x = link * linked + (1.f - link) * db[ch];
                const float u = thr - x;  // > 0: sinyal di bawah threshold
                float gr = 0.f;
                if (2.f * u > knee) gr = u * (ratio - 1.f);
                else if (knee > 0.f && 2.f * u > -knee) { const float t = u + knee * 0.5f; gr = (ratio - 1.f) * t * t / (2.f * knee); }
                gr = std::min(gr, range);
                gs_[ch].step(gr, aC, rC, hold);
                g[ch] = std::exp(-gs_[ch].gr * 0.11512925f);
            }
            grMax = std::max(grMax, gs_[0].gr);
            dl_.write(l[i]);
            dr_.write(r[i]);
            const float dryL = dl_.readInt(la), dryR = dr_.readInt(la);
            if (listen) { l[i] = kl; r[i] = kr; }
            else {
                l[i] = dryL * (1.f - mix) + dryL * g[0] * mix;
                r[i] = dryR * (1.f - mix) + dryR * g[1] * mix;
            }
        }
        setGr(grMax);
    }
    void reset() override { dl_.clear(); dr_.clear(); keyL_.reset(); keyR_.reset(); env_[0] = env_[1] = ms_[0] = ms_[1] = 0; gs_[0].reset(); gs_[1].reset(); }
protected:
    void prepare() override {
        maxLa_ = (int)(0.011f * sr_);
        dl_.init(maxLa_ + 2);
        dr_.init(maxLa_ + 2);
        keyL_.lastHp = keyL_.lastLp = keyR_.lastHp = keyR_.lastLp = -1.f;
    }
private:
    DelayLine dl_, dr_;
    KeyPath keyL_, keyR_;
    float env_[2] = {0, 0}, ms_[2] = {0, 0};
    GrSmoother gs_[2];
    int maxLa_ = 528;
};

// ---------------------------------------------------------------------------
// 4.9 Compressor
// ---------------------------------------------------------------------------
inline constexpr ParamDesc kCompDesc[] = {
    F("Threshold", -60, 0, -18, "dB"), F("Ratio", 1, 20, 4, ":1"), F("Knee", 0, 24, 6, "dB"),
    F("Attack", 0.1f, 200, 10, "ms"),  F("Release", 5, 2000, 120, "ms"),
    F("Makeup", 0, 24, 0, "dB"),       F("Mix", 0, 100, 100, "%"),
    F("Key HP", 20, 2000, 20, "Hz"),   T("Key Listen", false),
};

class Compressor : public Effect {
public:
    enum { THR, RATIO, KNEE, ATT, REL, MAKEUP, MIX, KHP, LISTEN };
    Compressor() : Effect(kCompDesc, 9) {}
    void process(float* l, float* r, int n, const FxContext& c) override {
        const float thr = P(THR), ratio = P(RATIO), knee = P(KNEE);
        const float aC = timeCoef(P(ATT), sr_), rC = timeCoef(P(REL), sr_);
        const float makeup = dbToLin(P(MAKEUP)), mix = P(MIX) * 0.01f;
        const bool listen = PB(LISTEN);
        const float invR = 1.f / ratio;
        keyL_.update(sr_, P(KHP), 20000.f);
        keyR_.update(sr_, P(KHP), 20000.f);
        float grMax = 0.f;
        for (int i = 0; i < n; ++i) {
            const float kl = keyL_.process(c.keyL ? c.keyL[i] : l[i]);
            const float kr = keyR_.process(c.keyL ? (c.keyR ? c.keyR[i] : c.keyL[i]) : r[i]);
            const float x = linToDb(std::max(std::fabs(kl), std::fabs(kr)));
            const float over = x - thr;
            float out;
            if (2.f * over < -knee) out = x;
            else if (knee > 0.f && 2.f * std::fabs(over) <= knee) {
                const float t = over + knee * 0.5f;
                out = x + (invR - 1.f) * t * t / (2.f * knee);
            } else out = thr + over * invR;
            const float target = std::max(0.f, x - out);
            gr_ = target + (gr_ - target) * (target > gr_ ? aC : rC);
            grMax = std::max(grMax, gr_);
            const float g = std::exp(-gr_ * 0.11512925f) * makeup;
            if (listen) { l[i] = kl; r[i] = kr; }
            else {
                l[i] = l[i] * (1.f - mix) + l[i] * g * mix;
                r[i] = r[i] * (1.f - mix) + r[i] * g * mix;
            }
        }
        setGr(grMax);
    }
    void reset() override { keyL_.reset(); keyR_.reset(); gr_ = 0; }
protected:
    void prepare() override { keyL_.lastHp = keyR_.lastHp = -1.f; keyL_.lastLp = keyR_.lastLp = -1.f; }
private:
    KeyPath keyL_, keyR_;
    float gr_ = 0.f;
};

// ---------------------------------------------------------------------------
// 4.13 De-esser
// ---------------------------------------------------------------------------
inline constexpr ParamDesc kDeEssDesc[] = {
    F("Frequency", 2000, 12000, 6500, "Hz"), F("Q", 0.5f, 4, 1.5f), F("Threshold", -60, 0, -30, "dB"),
    F("Range", 0, 24, 12, "dB"),             F("Attack", 0.05f, 10, 0.5f, "ms"),
    F("Release", 5, 200, 40, "ms"),          C("Mode", "Split,Wide", 0), T("Listen", false),
};

class DeEsser : public Effect {
public:
    enum { FREQ, Q, THR, RANGE, ATT, REL, MODE, LISTEN };
    DeEsser() : Effect(kDeEssDesc, 8) {}
    void process(float* l, float* r, int n, const FxContext& c) override {
        if (P(FREQ) != lastF_ || P(Q) != lastQ_) {
            lastF_ = P(FREQ); lastQ_ = P(Q);
            for (int ch = 0; ch < 3; ++ch) bp_[ch].bandpass(sr_, lastF_, lastQ_);
        }
        const float thr = P(THR), range = P(RANGE);
        const float aC = timeCoef(P(ATT), sr_), rC = timeCoef(P(REL), sr_);
        const bool split = PI(MODE) == 0, listen = PB(LISTEN);
        const float detRel = timeCoef(3.f, sr_);
        float grMax = 0.f;
        for (int i = 0; i < n; ++i) {
            const float bl = bp_[0].process(l[i]), br = bp_[1].process(r[i]);
            float k;
            if (c.keyL) k = bp_[2].process(0.5f * (c.keyL[i] + (c.keyR ? c.keyR[i] : c.keyL[i])));
            else k = 0.5f * (bl + br);
            const float a = std::fabs(k);
            env_ = a > env_ ? a : a + (env_ - a) * detRel;
            const float over = linToDb(env_) - thr;
            const float target = clampf(over, 0.f, range);
            gr_ = target + (gr_ - target) * (target > gr_ ? aC : rC);
            grMax = std::max(grMax, gr_);
            const float g = std::exp(-gr_ * 0.11512925f);
            if (listen) { l[i] = bl; r[i] = br; }
            else if (split) { l[i] = l[i] - bl + bl * g; r[i] = r[i] - br + br * g; }
            else { l[i] *= g; r[i] *= g; }
        }
        setGr(grMax);
    }
    void reset() override { for (auto& b : bp_) b.reset(); env_ = 0; gr_ = 0; }
protected:
    void prepare() override { lastF_ = -1.f; }
private:
    Biquad bp_[3];
    float env_ = 0, gr_ = 0, lastF_ = -1.f, lastQ_ = -1.f;
};

// ---------------------------------------------------------------------------
// 4.14 Limiter (lookahead; batas puncak terjamin <= Ceiling)
// ---------------------------------------------------------------------------
inline constexpr ParamDesc kLimDesc[] = {
    F("Gain", 0, 24, 0, "dB"), F("Ceiling", -12, 0, -0.3f, "dB"),
    F("Release", 10, 1000, 100, "ms"), F("Lookahead", 0, 5, 2, "ms"),
};

// Minimum geser (monotonic deque) berukuran tetap.
struct SlidingMin {
    std::vector<float> val;
    std::vector<int64_t> idx;
    int cap = 4, head = 0, count = 0, win = 1;
    int64_t t = 0;
    void init(int maxWin) {
        cap = maxWin + 3;
        val.assign(cap, 1.f);
        idx.assign(cap, 0);
        setWindow(1);
    }
    void setWindow(int w) { win = std::max(1, std::min(w, cap - 2)); head = 0; count = 0; t = 0; }
    inline float push(float v) {
        int tail = (head + count) % cap;
        while (count > 0) {
            const int last = (tail - 1 + cap) % cap;
            if (val[last] >= v) { tail = last; --count; } else break;
        }
        val[tail] = v;
        idx[tail] = t;
        ++count;
        while (idx[head] <= t - win) { head = (head + 1) % cap; --count; }
        ++t;
        return val[head];
    }
};

class Limiter : public Effect {
public:
    enum { GAIN, CEIL, REL, LOOK };
    Limiter() : Effect(kLimDesc, 4) {}
    void process(float* l, float* r, int n, const FxContext&) override {
        const float pre = dbToLin(P(GAIN)), ceil = dbToLin(P(CEIL));
        const float relC = timeCoef(P(REL), sr_);
        const int d = clampi((int)(P(LOOK) * 0.001f * sr_ + 0.5f), 0, maxD_);
        if (d != curD_) setLookahead(d);
        setLatency(d);
        float grMax = 0.f;
        for (int i = 0; i < n; ++i) {
            const float xl = l[i] * pre, xr = r[i] * pre;
            const float pk = std::max(std::fabs(xl), std::fabs(xr));
            const float req = pk > ceil ? ceil / pk : 1.f;
            const float m = min_.push(req);
            // boxcar atas minimum -> perubahan gain halus, tetap <= req sampel yang keluar
            boxSum_ += (double)m - (double)box_[bi_];
            box_[bi_] = m;
            if (++bi_ > curD_) bi_ = 0;
            const float avg = (float)(boxSum_ / (double)(curD_ + 1));
            const float rel = g_ + (1.f - g_) * (1.f - relC);
            g_ = std::min(avg, rel);
            dl_.write(xl);
            dr_.write(xr);
            const float ol = dl_.readInt(curD_) * g_, or_ = dr_.readInt(curD_) * g_;
            l[i] = clampf(ol, -ceil, ceil);
            r[i] = clampf(or_, -ceil, ceil);
            grMax = std::max(grMax, -linToDb(g_));
        }
        setGr(grMax);
    }
    void reset() override {
        dl_.clear(); dr_.clear();
        g_ = 1.f;
        setLookahead(curD_);
    }
protected:
    void prepare() override {
        maxD_ = (int)(0.005f * sr_ + 1.f);
        dl_.init(maxD_ + 2);
        dr_.init(maxD_ + 2);
        min_.init(maxD_ + 2);
        box_.assign(maxD_ + 2, 1.f);
        curD_ = 0;
    }
private:
    void setLookahead(int d) {
        curD_ = d;
        min_.setWindow(d + 1);
        std::fill(box_.begin(), box_.end(), 1.f);
        boxSum_ = (double)(d + 1);
        bi_ = 0;
    }
    DelayLine dl_, dr_;
    SlidingMin min_;
    std::vector<float> box_;
    double boxSum_ = 1.0;
    int bi_ = 0, curD_ = 0, maxD_ = 241;
    float g_ = 1.f;
};

// ---------------------------------------------------------------------------
// 4.11 Ducking (butuh Key Source; tanpa key tidak menurunkan apa pun)
// ---------------------------------------------------------------------------
inline constexpr ParamDesc kDuckDesc[] = {
    F("Threshold", -60, 0, -24, "dB"), F("Range", -60, 0, -12, "dB"),
    F("Attack", 0.1f, 500, 50, "ms"), F("Release", 10, 3000, 1200, "ms"),
};

class Ducking : public Effect {
public:
    enum { THR, RANGE, ATT, REL };
    Ducking() : Effect(kDuckDesc, 4) {}
    void process(float* l, float* r, int n, const FxContext& c) override {
        const bool active = c.keyL != nullptr && c.duckEnabled;
        const float thr = P(THR), depth = -P(RANGE);
        const float aC = timeCoef(P(ATT), sr_), rC = timeCoef(P(REL), sr_);
        const float detRel = timeCoef(10.f, sr_);
        for (int i = 0; i < n; ++i) {
            float target = 0.f;
            if (active) {
                const float k = 0.5f * (c.keyL[i] + (c.keyR ? c.keyR[i] : c.keyL[i]));
                const float a = std::fabs(k);
                env_ = a > env_ ? a : a + (env_ - a) * detRel;
                if (linToDb(env_) > thr) target = depth;
            }
            gr_ = target + (gr_ - target) * (target > gr_ ? aC : rC);
            const float g = std::exp(-gr_ * 0.11512925f);
            l[i] *= g;
            r[i] *= g;
        }
        setGr(gr_);
    }
    void reset() override { env_ = 0; gr_ = 0; }
private:
    float env_ = 0, gr_ = 0;
};

}  // namespace gp
