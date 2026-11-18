#include "fx_registry.h"

#include <cstdio>
#include <iterator>

#include "fx_dyn.h"
#include "fx_eq.h"
#include "fx_misc.h"
#include "fx_time.h"

#if defined(__SSE__) || defined(__x86_64__) || defined(_M_X64)
#include <xmmintrin.h>
#endif

namespace gp {

void enableFlushToZero() {
#if defined(__aarch64__)
    uint64_t fpcr;
    asm volatile("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= (1ULL << 24);  // FZ
    asm volatile("msr fpcr, %0" ::"r"(fpcr));
#elif defined(__arm__) && defined(__ARM_FP)
    uint32_t fpscr;
    asm volatile("vmrs %0, fpscr" : "=r"(fpscr));
    fpscr |= (1u << 24);  // FZ
    asm volatile("vmsr fpscr, %0" ::"r"(fpscr));
#elif defined(__SSE__) || defined(__x86_64__) || defined(_M_X64)
    _mm_setcsr(_mm_getcsr() | 0x8040);  // FTZ + DAZ
#endif
}

float noteBeats(int idx) {
    static const float kBeats[16] = {16.f, 8.f, 4.f, 2.f, 1.f, 0.5f, 0.25f, 0.125f,
                                     4.f / 3.f, 2.f / 3.f, 1.f / 3.f, 1.f / 6.f,
                                     3.f, 1.5f, 0.75f, 0.375f};
    return kBeats[clampi(idx, 0, 15)];
}

namespace {
#define D(arr) arr, (int)std::size(arr)
const FxInfo kInfos[FX_COUNT] = {
    {"EQ", "Equaliser", false, D(kEqDesc)},
    {"Gate", "Dynamics", true, D(kGateDesc)},
    {"Expander", "Dynamics", true, D(kExpDesc)},
    {"Compressor", "Dynamics", true, D(kCompDesc)},
    {"De-esser", "Dynamics", true, D(kDeEssDesc)},
    {"Limiter", "Dynamics", false, D(kLimDesc)},
    {"Ducking", "Dynamics", true, D(kDuckDesc)},
    {"Chorus", "Modulation", false, D(kChorusDesc)},
    {"Delay", "Time and space", false, D(kDelayDesc)},
    {"Reverb", "Time and space", false, D(kReverbDesc)},
    {"Saturator", "Colour", false, D(kSatDesc)},
    {"Tremolo", "Modulation", false, D(kTremDesc)},
    {"Auto Pan", "Modulation", false, D(kAPanDesc)},
    {"Utility", "Utility", false, D(kUtilDesc)},
    {"Transposer", "Pitch", false, D(kTransDesc)},
    {"Hum Remover", "Cleanup", false, D(kHumDesc)},
    {"Bitcrusher", "Colour", false, D(kCrushDesc)},
    {"Test Tone", "Utility", false, D(kToneDesc)},
};
#undef D
}  // namespace

int fxCount() { return FX_COUNT; }

const FxInfo* fxInfo(int type) { return (type >= 0 && type < FX_COUNT) ? &kInfos[type] : nullptr; }

std::unique_ptr<Effect> makeEffect(int type, float sr) {
    std::unique_ptr<Effect> e;
    switch (type) {
        case FX_EQ: e = std::make_unique<EQ>(); break;
        case FX_GATE: e = std::make_unique<Gate>(); break;
        case FX_EXPANDER: e = std::make_unique<Expander>(); break;
        case FX_COMPRESSOR: e = std::make_unique<Compressor>(); break;
        case FX_DEESSER: e = std::make_unique<DeEsser>(); break;
        case FX_LIMITER: e = std::make_unique<Limiter>(); break;
        case FX_DUCKING: e = std::make_unique<Ducking>(); break;
        case FX_CHORUS: e = std::make_unique<Chorus>(); break;
        case FX_DELAY: e = std::make_unique<Delay>(); break;
        case FX_REVERB: e = std::make_unique<Reverb>(); break;
        case FX_SATURATOR: e = std::make_unique<Saturator>(); break;
        case FX_TREMOLO: e = std::make_unique<Tremolo>(); break;
        case FX_AUTOPAN: e = std::make_unique<AutoPan>(); break;
        case FX_UTILITY: e = std::make_unique<Utility>(); break;
        case FX_TRANSPOSER: e = std::make_unique<Transposer>(); break;
        case FX_HUM: e = std::make_unique<HumRemover>(); break;
        case FX_BITCRUSHER: e = std::make_unique<Bitcrusher>(); break;
        case FX_TESTTONE: e = std::make_unique<TestTone>(); break;
        default: return nullptr;
    }
    e->setSampleRate(sr);
    return e;
}

std::string describeEffects() {
    std::string out;
    char buf[256];
    for (int t = 0; t < FX_COUNT; ++t) {
        const FxInfo& fi = kInfos[t];
        std::snprintf(buf, sizeof buf, "E|%d|%s|%s|%d\n", t, fi.name, fi.category, fi.usesKey ? 1 : 0);
        out += buf;
        for (int i = 0; i < fi.nParams; ++i) {
            const ParamDesc& p = fi.params[i];
            std::snprintf(buf, sizeof buf, "P|%s|%s|%d|%.6g|%.6g|%.6g|%s|%s\n", p.group, p.name, p.kind,
                          (double)p.mn, (double)p.mx, (double)p.def, p.unit, p.choices);
            out += buf;
        }
    }
    return out;
}

}  // namespace gp
