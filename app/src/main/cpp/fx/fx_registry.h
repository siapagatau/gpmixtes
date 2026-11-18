// GP Mix (Android) - daftar efek bawaan: tipe -> pabrik, dan deskripsi parameter untuk UI.
#pragma once
#include <memory>
#include <string>

#include "fx_core.h"

namespace gp {

enum FxType {
    FX_EQ = 0, FX_GATE, FX_EXPANDER, FX_COMPRESSOR, FX_DEESSER, FX_LIMITER, FX_DUCKING,
    FX_CHORUS, FX_DELAY, FX_REVERB, FX_SATURATOR, FX_TREMOLO, FX_AUTOPAN, FX_UTILITY,
    FX_TRANSPOSER, FX_HUM, FX_BITCRUSHER, FX_TESTTONE, FX_COUNT
};

struct FxInfo {
    const char* name;
    const char* category;  // Dynamics, Cleanup, Equaliser, Time and space, Modulation, Pitch, Colour, Utility
    bool usesKey;          // punya Key Source (side chain)
    const ParamDesc* params;
    int nParams;
};

int fxCount();
const FxInfo* fxInfo(int type);  // nullptr bila tipe tidak valid
std::unique_ptr<Effect> makeEffect(int type, float sr);  // nullptr bila tidak valid

// Teks deskripsi seluruh efek. Format per baris, dipisah '|':
//   E|type|name|category|usesKey(0/1)
//   P|group|name|kind(0 float,1 toggle,2 choice)|min|max|default|unit|choices(dipisah koma)
std::string describeEffects();

}  // namespace gp
