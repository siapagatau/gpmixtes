// GP Mix (Android) - EQ enam band (bab 4.2): HP, empat band parametrik (B1 shelf rendah, B6 shelf tinggi), LP.
#pragma once
#include "fx_core.h"

namespace gp {

#define GP_BAND(n, f, shape)                                                               \
    T("On", true, "Band " #n), F("Freq", 20, 20000, f, "Hz", "Band " #n),                  \
        F("Gain", -18, 18, 0, "dB", "Band " #n), F("Q", 0.1f, 10, 1.f, "", "Band " #n),    \
        C("Shape", "Bell,Low Shelf,High Shelf", shape, "Band " #n)

inline constexpr ParamDesc kEqDesc[] = {
    T("On", false, "High-pass"), F("Freq", 20, 1000, 30, "Hz", "High-pass"),
    C("Slope", "12 dB,24 dB,36 dB,48 dB", 0, "High-pass"),
    T("On", false, "Low-pass"),  F("Freq", 1000, 20000, 16000, "Hz", "Low-pass"),
    C("Slope", "12 dB,24 dB,36 dB,48 dB", 0, "Low-pass"),
    GP_BAND(1, 80, 1),   GP_BAND(2, 250, 0),  GP_BAND(3, 800, 0),
    GP_BAND(4, 2500, 0), GP_BAND(5, 6000, 0), GP_BAND(6, 12000, 2),
    F("Out Gain", -12, 12, 0, "dB", "Output"),
};
#undef GP_BAND

class EQ : public Effect {
public:
    enum { HP_ON, HP_F, HP_S, LP_ON, LP_F, LP_S, B0 = 6, OUT = 36 };
    EQ() : Effect(kEqDesc, 37) {}

    void process(float* l, float* r, int n, const FxContext&) override {
        update();
        if (hpOn_) hp_.process(l, r, n);
        if (lpOn_) lp_.process(l, r, n);
        for (int b = 0; b < 6; ++b) {
            if (!bandActive_[b]) continue;
            for (int i = 0; i < n; ++i) { l[i] = band_[b][0].process(l[i]); r[i] = band_[b][1].process(r[i]); }
        }
        const float tgt = dbToLin(P(OUT));
        for (int i = 0; i < n; ++i) {
            const float g = outSm_.step(tgt, 0.995f);
            l[i] *= g;
            r[i] *= g;
        }
    }
    void reset() override {
        hp_.reset(); lp_.reset();
        for (auto& b : band_) { b[0].reset(); b[1].reset(); }
    }
protected:
    void prepare() override { for (auto& v : last_) v = -12345.f; }
private:
    void update() {
        bool dirty = false;
        for (int i = 0; i < 36; ++i) {
            const float v = P(i);
            if (v != last_[i]) { last_[i] = v; dirty = true; }
        }
        if (!dirty) return;
        hpOn_ = PB(HP_ON);
        lpOn_ = PB(LP_ON);
        hp_.set(true, sr_, P(HP_F), PI(HP_S));
        lp_.set(false, sr_, P(LP_F), PI(LP_S));
        for (int b = 0; b < 6; ++b) {
            const int o = B0 + b * 5;
            const bool on = PB(o);
            const float f = P(o + 1), g = P(o + 2), q = P(o + 3);
            const int shape = PI(o + 4);
            bandActive_[b] = on && std::fabs(g) > 0.01f;
            if (!bandActive_[b]) continue;
            for (int c = 0; c < 2; ++c) {
                if (shape == 1) band_[b][c].lowShelf(sr_, f, q, g);
                else if (shape == 2) band_[b][c].highShelf(sr_, f, q, g);
                else band_[b][c].peaking(sr_, f, q, g);
            }
        }
    }
    FilterCascade hp_, lp_;
    Biquad band_[6][2];
    bool hpOn_ = false, lpOn_ = false, bandActive_[6] = {};
    float last_[36] = {};
    Smooth outSm_;
};

}  // namespace gp
