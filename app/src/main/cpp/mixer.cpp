#include "mixer.h"

#include <chrono>
#include <cstring>
#include <thread>

namespace gp {

namespace {
inline void applyRamp(float* l, float* r, int n, float g0, float g1) {
    if (g0 == g1) {
        if (g0 == 1.f) return;
        for (int i = 0; i < n; ++i) { l[i] *= g0; r[i] *= g0; }
        return;
    }
    const float d = (g1 - g0) / (float)n;
    float g = g0;
    for (int i = 0; i < n; ++i) { g += d; l[i] *= g; r[i] *= g; }
}
inline void addRamp(float* dl, float* dr, const float* l, const float* r, int n, float g0, float g1) {
    if (g0 == 0.f && g1 == 0.f) return;
    const float d = (g1 - g0) / (float)n;
    float g = g0;
    for (int i = 0; i < n; ++i) { g += d; dl[i] += l[i] * g; dr[i] += r[i] * g; }
}
inline float faderLin(float db) { return db <= -59.9f ? 0.f : dbToLin(db); }
inline int16_t toI16(float x) { return (int16_t)(clampf(x, -1.f, 1.f) * 32767.f); }
}  // namespace

Mixer::Mixer() : appRing_(1 << 17) {
    std::memset(hwIn_, 0, sizeof hwIn_);
    std::memset(busCur_, 0, sizeof busCur_);
    std::memset(busPrev_, 0, sizeof busPrev_);
    std::memset(grp_, 0, sizeof grp_);
    std::memset(mst_, 0, sizeof mst_);
    safety_.setSampleRate(sr_);
    safety_.setParam(Limiter::CEIL, -0.3f);
    safety_.setParam(Limiter::LOOK, 1.f);
}

Mixer::~Mixer() {
    for (auto& c : chans_) delete c;
    for (auto& p : pads_) delete p.data.load();
    delete player_.load();
    delete retro_.load();
    delete rec_.load();
}

bool Mixer::quiesce() {
    if (!active_.load()) return true;
    const uint32_t e0 = epoch_.load();
    for (int i = 0; i < 150; ++i) {
        if ((uint32_t)(epoch_.load() - e0) >= 2u) return true;
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    // Thread audio macet: jangan bebaskan memori; lebih baik bocor sedikit daripada crash.
    return false;
}

void Mixer::rebuildLists() {
    audio_.clear(); aux_.clear(); group_.clear();
    master_ = nullptr;
    for (int i = 0; i < kMaxChannels; ++i) {
        Channel* c = chans_[i];
        if (!c) continue;
        switch (c->type) {
            case CH_AUDIO: audio_.push_back(c); break;
            case CH_AUX: aux_.push_back(c); break;
            case CH_GROUP: group_.push_back(c); break;
            default: if (!master_) master_ = c; break;
        }
    }
}

void Mixer::setSampleRate(float sr) {
    retroEnable(false);
    std::lock_guard<std::mutex> u(uiMu_);
    std::lock_guard<std::mutex> g(graphMu_);
    sr_ = clampf(sr, 8000.f, 192000.f);
    fxc_.sr = sr_;
    for (auto* c : chans_) {
        if (!c) continue;
        c->lastHpf = c->lastLpf = -1.f;
        c->hpf.reset(); c->lpf.reset();
        for (auto& in : c->inserts) if (in.fx) in.fx->setSampleRate(sr_);
    }
    safety_.setSampleRate(sr_);
}

bool Mixer::addChannel(int id, int type) {
    if (id < 0 || id >= kMaxChannels || type < CH_AUDIO || type > CH_MASTER) return false;
    std::lock_guard<std::mutex> u(uiMu_);
    if (chans_[id]) return false;
    if (type == CH_MASTER && master_) return false;
    Channel* c = new Channel();
    c->id = id;
    c->type = type;
    c->outMask.store(type == CH_MASTER ? 0u : 1u);
    std::lock_guard<std::mutex> g(graphMu_);
    chans_[id] = c;
    rebuildLists();
    return true;
}

bool Mixer::removeChannel(int id) {
    if (id < 0 || id >= kMaxChannels) return false;
    std::unique_lock<std::mutex> u(uiMu_);
    Channel* c = chans_[id];
    if (!c) return false;
    {
        std::lock_guard<std::mutex> g(graphMu_);
        chans_[id] = nullptr;
        rebuildLists();
        for (auto* o : chans_)
            if (o) for (auto& in : o->inserts) if (in.keySource.load() == id) in.keySource.store(-1);
    }
    delete c;  // thread audio hanya memakai channel di bawah graphMu_
    return true;
}

void Mixer::setChannelParam(int id, int param, float v) {
    if (!std::isfinite(v) || id < 0 || id >= kMaxChannels) return;
    std::lock_guard<std::mutex> u(uiMu_);
    Channel* c = chans_[id];
    if (!c) return;
    switch (param) {
        case CP_FADER_DB: c->faderDb.store(clampf(v, -60.f, 6.f)); break;
        case CP_PAN: c->pan.store(clampf(v, -1.f, 1.f)); break;
        case CP_TRIM_DB: c->trimDb.store(clampf(v, -24.f, 24.f)); break;
        case CP_MUTE: c->mute.store(v > 0.5f); break;
        case CP_SOLO: c->solo.store(v > 0.5f); break;
        case CP_SAFE: c->safe.store(v > 0.5f); break;
        case CP_POLARITY: c->polarity.store(v > 0.5f); break;
        case CP_STEREO: c->stereo.store(v > 0.5f); break;
        case CP_HPF_ON: c->hpfOn.store(v > 0.5f); break;
        case CP_HPF_HZ: c->hpfHz.store(clampf(v, 20.f, 1000.f)); break;
        case CP_HPF_SLOPE: c->hpfSlope.store(clampi((int)v, 0, 3)); break;
        case CP_LPF_ON: c->lpfOn.store(v > 0.5f); break;
        case CP_LPF_HZ: c->lpfHz.store(clampf(v, 1000.f, 20000.f)); break;
        case CP_LPF_SLOPE: c->lpfSlope.store(clampi((int)v, 0, 3)); break;
        case CP_OUT_MASK: c->outMask.store(v < 0.f ? 0u : (uint32_t)v); break;
        case CP_GROUP_INDEX: c->groupIndex.store(clampi((int)v, 0, kNumGroups - 1)); break;
        default: break;
    }
}

void Mixer::setInput(int id, int kind, int index, int extraMask) {
    if (id < 0 || id >= kMaxChannels) return;
    std::lock_guard<std::mutex> u(uiMu_);
    Channel* c = chans_[id];
    if (!c) return;
    c->inKind.store(clampi(kind, IN_NONE, IN_APP));
    c->inIndex.store(clampi(index, 0, 15));
    c->extraMask.store(extraMask & 7);
}

void Mixer::setSend(int id, int slot, int dest, float levelDb, bool mute, bool pre) {
    if (id < 0 || id >= kMaxChannels || slot < 0 || slot >= kMaxSends || !std::isfinite(levelDb)) return;
    std::lock_guard<std::mutex> u(uiMu_);
    Channel* c = chans_[id];
    if (!c) return;
    Send& s = c->sends[slot];
    s.dest.store(clampi(dest, -1, kNumBuses - 1));
    s.levelDb.store(clampf(levelDb, -60.f, 12.f));
    s.mute.store(mute);
    s.pre.store(pre);
}

bool Mixer::setInsert(int id, int slot, int type) {
    if (id < 0 || id >= kMaxChannels || slot < 0 || slot >= kMaxInserts) return false;
    std::lock_guard<std::mutex> u(uiMu_);
    Channel* c = chans_[id];
    if (!c) return false;
    std::unique_ptr<Effect> nf;
    if (type >= 0) {
        nf = makeEffect(type, sr_);
        if (!nf) return false;
    }
    Insert& in = c->inserts[slot];
    {
        std::lock_guard<std::mutex> g(graphMu_);
        in.fx.swap(nf);
        in.type.store(type);
        in.fade = 1.f;
    }
    return true;  // nf (efek lama) dibebaskan di sini, di luar graphMu_
}

void Mixer::setFxParam(int id, int slot, int idx, float v) {
    if (id < 0 || id >= kMaxChannels || slot < 0 || slot >= kMaxInserts) return;
    std::lock_guard<std::mutex> u(uiMu_);
    Channel* c = chans_[id];
    if (c && c->inserts[slot].fx) c->inserts[slot].fx->setParam(idx, v);
}

void Mixer::setInsertFlags(int id, int slot, bool bypass, int keySource) {
    if (id < 0 || id >= kMaxChannels || slot < 0 || slot >= kMaxInserts) return;
    std::lock_guard<std::mutex> u(uiMu_);
    Channel* c = chans_[id];
    if (!c) return;
    c->inserts[slot].bypass.store(bypass);
    c->inserts[slot].keySource.store(keySource >= 0 && keySource < kMaxChannels ? keySource : -1);
}

float Mixer::fxGainReduction(int id, int slot) {
    if (id < 0 || id >= kMaxChannels || slot < 0 || slot >= kMaxInserts) return 0.f;
    std::lock_guard<std::mutex> u(uiMu_);
    Channel* c = chans_[id];
    return (c && c->inserts[slot].fx) ? c->inserts[slot].fx->gainReductionDb() : 0.f;
}

// ---------------------------------------------------------------------------
// Pads
// ---------------------------------------------------------------------------
void Mixer::loadPad(int idx, const float* d, int frames, int channels, int rate) {
    if (idx < 0 || idx >= kNumPads || !d || frames <= 0 || channels < 1 || rate < 8000) return;
    PadData* nd = new PadData();
    nd->L.resize(frames);
    nd->R.resize(frames);
    float pk = 0.f;
    for (int i = 0; i < frames; ++i) {
        const float l = d[(size_t)i * channels];
        const float r = channels > 1 ? d[(size_t)i * channels + 1] : l;
        nd->L[i] = l;
        nd->R[i] = r;
        pk = std::max(pk, std::max(std::fabs(l), std::fabs(r)));
    }
    nd->rate = (float)rate;
    nd->norm = pk > 1e-6f ? 0.8912509f / pk : 1.f;
    std::lock_guard<std::mutex> u(uiMu_);
    PadData* old = pads_[idx].data.exchange(nd);
    if (quiesce()) delete old;
}

void Mixer::clearPad(int idx) {
    if (idx < 0 || idx >= kNumPads) return;
    std::lock_guard<std::mutex> u(uiMu_);
    PadData* old = pads_[idx].data.exchange(nullptr);
    if (quiesce()) delete old;
}

void Mixer::setPadParam(int idx, int param, float v) {
    if (idx < 0 || idx >= kNumPads || !std::isfinite(v)) return;
    Pad& p = pads_[idx];
    switch (param) {
        case PP_GAIN_DB: p.gainDb.store(clampf(v, -60.f, 6.f)); break;
        case PP_START: p.start.store(clampf(v, 0.f, 1.f)); break;
        case PP_END: p.end.store(clampf(v, 0.f, 1.f)); break;
        case PP_CHOKE: p.choke.store(clampi((int)v, 0, 8)); break;
        case PP_NORMALIZE: p.normalize.store(v > 0.5f); break;
        default: break;
    }
}

void Mixer::triggerPad(int idx, float velocity) {
    if (idx < 0 || idx >= kNumPads) return;
    pads_[idx].vel.store(clampf(velocity, 0.f, 1.f));
    trigMask_.fetch_or(1u << idx);
}

void Mixer::renderPads(int n) {
    std::fill_n(padsL_, n, 0.f);
    std::fill_n(padsR_, n, 0.f);
    const uint32_t stop = stopMask_.exchange(0), trig = trigMask_.exchange(0);
    const int fadeTotal = std::max(1, (int)(0.005f * sr_));
    const float master = dbToLin(padsMasterDb_.load());
    uint32_t playing = 0;
    for (int i = 0; i < kNumPads; ++i) {
        Pad& p = pads_[i];
        const uint32_t bit = 1u << i;
        if ((stop & bit) && p.active && p.fadeLeft < 0) p.fadeLeft = fadeTotal;
        if (trig & bit) {
            PadData* d = p.data.load(std::memory_order_acquire);
            if (d && !d->L.empty()) {
                const int g = p.choke.load();
                if (g > 0)
                    for (int j = 0; j < kNumPads; ++j)
                        if (j != i && pads_[j].active && pads_[j].choke.load() == g && pads_[j].fadeLeft < 0)
                            pads_[j].fadeLeft = fadeTotal;
                p.v = d;
                p.pos = (double)clampf(p.start.load(), 0.f, 1.f) * (double)d->L.size();
                p.active = true;
                p.fadeLeft = -1;
            }
        }
        if (!p.active) continue;
        if (p.data.load(std::memory_order_acquire) != p.v) { p.active = false; continue; }
        const int frames = (int)p.v->L.size();
        const int endF = std::max(2, (int)(clampf(p.end.load(), 0.f, 1.f) * (float)frames));
        const float gain = dbToLin(p.gainDb.load()) * (p.normalize.load() ? p.v->norm : 1.f) * p.vel.load() * master;
        const double step = (double)p.v->rate / (double)sr_;
        for (int k = 0; k < n; ++k) {
            const int i0 = (int)p.pos;
            if (i0 >= endF - 1 || i0 >= frames - 1) { p.active = false; break; }
            float env = 1.f;
            if (p.fadeLeft >= 0) {
                if (p.fadeLeft == 0) { p.active = false; break; }
                env = (float)p.fadeLeft / (float)fadeTotal;
                --p.fadeLeft;
            }
            const float f = (float)(p.pos - i0);
            const float a = p.v->L[i0] + (p.v->L[i0 + 1] - p.v->L[i0]) * f;
            const float b = p.v->R[i0] + (p.v->R[i0 + 1] - p.v->R[i0]) * f;
            padsL_[k] += a * gain * env;
            padsR_[k] += b * gain * env;
            p.pos += step;
        }
        if (p.active) playing |= bit;
    }
    padPlaying_.store(playing);
    float pk = 0.f;
    for (int i = 0; i < n; ++i) pk = std::max(pk, std::max(std::fabs(padsL_[i]), std::fabs(padsR_[i])));
    atomicMax(padsPeak_, pk);
}

// ---------------------------------------------------------------------------
// Player
// ---------------------------------------------------------------------------
void Mixer::loadPlayer(const int16_t* stereo, int frames, int rate) {
    if (!stereo || frames <= 0 || rate < 8000) return;
    PlayerData* nd = new PlayerData();
    nd->pcm.assign(stereo, stereo + (size_t)frames * 2);
    nd->frames = frames;
    nd->rate = rate;
    std::lock_guard<std::mutex> u(uiMu_);
    plyPlaying_.store(false);
    plySeek_.store(0);
    PlayerData* old = player_.exchange(nd);
    if (quiesce()) delete old;
}

void Mixer::clearPlayer() {
    std::lock_guard<std::mutex> u(uiMu_);
    plyPlaying_.store(false);
    PlayerData* old = player_.exchange(nullptr);
    if (quiesce()) delete old;
}

void Mixer::renderPlayer(int n) {
    std::fill_n(plyL_, n, 0.f);
    std::fill_n(plyR_, n, 0.f);
    PlayerData* d = player_.load(std::memory_order_acquire);
    if (d != plyV_) { plyV_ = d; plyPos_ = 0.0; }
    if (!d) { plyPosOut_.store(0); return; }
    const int64_t seek = plySeek_.exchange(-1);
    if (seek >= 0) plyPos_ = (double)std::min<int64_t>(seek, d->frames);
    if (!plyPlaying_.load()) { plyPosOut_.store((int64_t)plyPos_); return; }
    const double step = (double)d->rate / (double)sr_;
    const float g = dbToLin(plyDb_.load()) * (1.f / 32768.f);
    const bool loop = plyLoop_.load();
    for (int i = 0; i < n; ++i) {
        int i0 = (int)plyPos_;
        if (i0 >= d->frames - 1) {
            if (loop) { plyPos_ = 0.0; i0 = 0; }
            else { plyPlaying_.store(false); plyPos_ = 0.0; break; }
        }
        const float f = (float)(plyPos_ - i0);
        const int16_t* p = &d->pcm[(size_t)i0 * 2];
        plyL_[i] = ((float)p[0] + ((float)p[2] - (float)p[0]) * f) * g;
        plyR_[i] = ((float)p[1] + ((float)p[3] - (float)p[1]) * f) * g;
        plyPos_ += step;
    }
    plyPosOut_.store((int64_t)plyPos_);
}

// ---------------------------------------------------------------------------
// Retrospective Record & rec tap
// ---------------------------------------------------------------------------
bool Mixer::retroEnable(bool on) {
    std::lock_guard<std::mutex> l(retroMu_);
    if (on) {
        if (retro_.load()) { retroOn_.store(true); return true; }
        RetroBuf* rb = new RetroBuf();
        rb->cap = (int)(300.f * sr_);
        rb->data.assign((size_t)rb->cap * 2, 0);
        retro_.store(rb);
        retroOn_.store(true);
        return true;
    }
    retroOn_.store(false);
    RetroBuf* old = retro_.exchange(nullptr);
    if (old) {
        if (quiesce()) delete old;
    }
    return true;
}

int Mixer::retroBegin() {
    std::lock_guard<std::mutex> l(retroMu_);
    RetroBuf* rb = retro_.load();
    if (!rb) { retroAvail_ = 0; return 0; }
    const uint64_t w = rb->written.load(std::memory_order_acquire);
    retroAvail_ = (int)std::min<uint64_t>(w, (uint64_t)rb->cap);
    retroBase_ = w - (uint64_t)retroAvail_;
    return retroAvail_;
}

int Mixer::retroRead(int offset, int16_t* dst, int frames) {
    std::lock_guard<std::mutex> l(retroMu_);
    RetroBuf* rb = retro_.load();
    if (!rb || offset < 0 || frames <= 0 || offset >= retroAvail_) return 0;
    const int n = std::min(frames, retroAvail_ - offset);
    for (int i = 0; i < n; ++i) {
        const size_t k = (size_t)((retroBase_ + (uint64_t)offset + (uint64_t)i) % (uint64_t)rb->cap) * 2;
        dst[i * 2] = rb->data[k];
        dst[i * 2 + 1] = rb->data[k + 1];
    }
    return n;
}

bool Mixer::recStart(int source) {
    std::lock_guard<std::mutex> l(recMu_);
    if (rec_.load()) return true;
    recSrc_.store(source);
    recOver_.store(0);
    rec_.store(new SpscRing((int)(sr_ * 4.f)));
    return true;
}

void Mixer::recStop() {
    std::lock_guard<std::mutex> l(recMu_);
    SpscRing* old = rec_.exchange(nullptr);
    if (old) {
        std::lock_guard<std::mutex> u(uiMu_);
        if (quiesce()) delete old;
    }
}

int Mixer::recRead(float* dst, int maxFrames) {
    std::lock_guard<std::mutex> l(recMu_);
    SpscRing* r = rec_.load();
    return r ? r->pop(dst, maxFrames) : 0;
}

// ---------------------------------------------------------------------------
// Meter
// ---------------------------------------------------------------------------
void Mixer::readMeters(float* out) {
    std::lock_guard<std::mutex> u(uiMu_);
    for (int i = 0; i < kMaxChannels; ++i) {
        Channel* c = chans_[i];
        if (!c) { out[2 * i] = out[2 * i + 1] = 0.f; out[2 * kMaxChannels + i] = 0.f; continue; }
        out[2 * i] = c->peakL.exchange(0.f);
        out[2 * i + 1] = c->peakR.exchange(0.f);
        int lat = c->latency.load();
        if (c->type == CH_MASTER) lat += safety_.latency();
        out[2 * kMaxChannels + i] = (float)lat * 1000.f / sr_;
    }
}

// ---------------------------------------------------------------------------
// Render
// ---------------------------------------------------------------------------
void Mixer::render(const float* hw, int hwCh, float* out, int frames) {
    epoch_.fetch_add(1);
    std::unique_lock<std::mutex> lk(graphMu_, std::try_to_lock);
    if (!lk.owns_lock() || !master_) {
        std::fill_n(out, (size_t)frames * 2, 0.f);
        return;
    }
    hwChN_ = hw ? clampi(hwCh, 0, kMaxHwIn) : 0;
    const int stride = hw ? hwCh : 0;
    int done = 0;
    while (done < frames) {
        const int n = std::min(kBlock, frames - done);
        renderChunk(hw ? hw + (size_t)done * stride : nullptr, stride, out + (size_t)done * 2, n);
        done += n;
    }
}

void Mixer::gatherInput(Channel& c, float* l, float* r, int n) {
    std::fill_n(l, n, 0.f);
    std::fill_n(r, n, 0.f);
    auto add = [&](const float* a, const float* b) {
        for (int i = 0; i < n; ++i) { l[i] += a[i]; r[i] += b[i]; }
    };
    if (c.type == CH_GROUP) { const int g = c.groupIndex.load(); add(grp_[g][0], grp_[g][1]); return; }
    if (c.type == CH_MASTER) { add(mst_[0], mst_[1]); return; }
    const int kind = c.inKind.load(), idx = c.inIndex.load();
    switch (kind) {
        case IN_HW:
            if (idx < hwChN_) {
                if (c.stereo.load()) {
                    add(hwIn_[idx], idx + 1 < hwChN_ ? hwIn_[idx + 1] : hwIn_[idx]);
                } else {
                    add(hwIn_[idx], hwIn_[idx]);
                }
            }
            break;
        case IN_BUS: {
            const int b = idx & (kNumBuses - 1);
            if (c.type == CH_AUX) add(busCur_[b][0], busCur_[b][1]);
            else add(busPrev_[b][0], busPrev_[b][1]);
            break;
        }
        case IN_PADS: add(padsL_, padsR_); break;
        case IN_PLAYER: add(plyL_, plyR_); break;
        case IN_APP: add(appL_, appR_); break;
        default: break;
    }
    const int ex = c.extraMask.load();
    if ((ex & SRC_PADS) && kind != IN_PADS) add(padsL_, padsR_);
    if ((ex & SRC_PLAYER) && kind != IN_PLAYER) add(plyL_, plyR_);
    if ((ex & SRC_APP) && kind != IN_APP) add(appL_, appR_);
}

void Mixer::route(Channel& c, const float* l, const float* r, int n) {
    uint32_t mask = c.outMask.load();
    if (c.type == CH_GROUP) mask &= 1u;  // group hanya ke Master
    if (mask & 1u) addRamp(mst_[0], mst_[1], l, r, n, 1.f, 1.f);
    for (int g = 0; g < kNumGroups; ++g)
        if (mask & outGroup(g)) addRamp(grp_[g][0], grp_[g][1], l, r, n, 1.f, 1.f);
    for (int b = 0; b < kNumBuses; ++b) {
        if (!(mask & outBus(b))) continue;
        if (c.type == CH_AUDIO && c.inKind.load() == IN_BUS && (c.inIndex.load() & (kNumBuses - 1)) == b) continue;  // cegah loop
        addRamp(busCur_[b][0], busCur_[b][1], l, r, n, 1.f, 1.f);
    }
}

void Mixer::runChannel(Channel& c, float* l, float* r, int n, bool anySolo) {
    const bool isMaster = c.type == CH_MASTER;
    // 1. trim + polaritas
    const float trimT = dbToLin(c.trimDb.load()) * (c.polarity.load() ? -1.f : 1.f);
    const bool muted = c.mute.load() || (anySolo && c.type == CH_AUDIO && !c.solo.load() && !c.safe.load());
    const float mT = muted ? 0.f : 1.f, fT = faderLin(c.faderDb.load());
    const bool monoSrc = c.type == CH_AUDIO && c.inKind.load() == IN_HW && !c.stereo.load() && c.extraMask.load() == 0;
    float gl, gr;
    if (isMaster || c.type == CH_GROUP) {
        const float p = c.pan.load();
        gl = p > 0.f ? 1.f - p : 1.f; gr = p < 0.f ? 1.f + p : 1.f;
    } else if (monoSrc) {
        const float a = (clampf(c.pan.load(), -1.f, 1.f) + 1.f) * 0.25f * kPi;
        const float k = panLawDb_.load() / -3.0103f;
        gl = k == 1.f ? std::cos(a) : std::pow(std::max(0.f, std::cos(a)), k);
        gr = k == 1.f ? std::sin(a) : std::pow(std::max(0.f, std::sin(a)), k);
    } else {
        const float p = c.pan.load();
        gl = p > 0.f ? 1.f - p : 1.f; gr = p < 0.f ? 1.f + p : 1.f;
    }
    if (!c.gInit) { c.gTrim = trimT; c.gMute = mT; c.gFader = fT; c.gPanL = gl; c.gPanR = gr; for (auto& s : c.sends) s.gain = 0.f; c.gInit = true; }
    applyRamp(l, r, n, c.gTrim, trimT);
    c.gTrim = trimT;

    // 2. filter
    if (c.hpfOn.load()) {
        const float hz = c.hpfHz.load(); const int sl = c.hpfSlope.load();
        if (hz != c.lastHpf || sl != c.lastHpfSlope) { c.hpf.set(true, sr_, hz, sl); c.lastHpf = hz; c.lastHpfSlope = sl; }
        c.hpf.process(l, r, n);
    } else if (c.lastHpf >= 0.f) { c.hpf.reset(); c.lastHpf = -1.f; }
    if (c.lpfOn.load()) {
        const float hz = c.lpfHz.load(); const int sl = c.lpfSlope.load();
        if (hz != c.lastLpf || sl != c.lastLpfSlope) { c.lpf.set(false, sr_, hz, sl); c.lastLpf = hz; c.lastLpfSlope = sl; }
        c.lpf.process(l, r, n);
    } else if (c.lastLpf >= 0.f) { c.lpf.reset(); c.lastLpf = -1.f; }

    // 3. insert
    int lat = 0;
    for (int s = 0; s < kMaxInserts; ++s) {
        Insert& in = c.inserts[s];
        Effect* fx = in.fx.get();
        if (!fx) continue;
        const float target = in.bypass.load() ? 0.f : 1.f;
        if (in.fade == 0.f && target == 0.f) continue;
        FxContext cx = fxc_;
        cx.bpm = bpm_.load();
        cx.duckEnabled = duck_.load();
        const int ks = in.keySource.load();
        if (ks >= 0 && ks < kMaxChannels && ks != c.id && chans_[ks]) { cx.keyL = chans_[ks]->keyL; cx.keyR = chans_[ks]->keyR; }
        if (in.fade == 1.f && target == 1.f) {
            fx->process(l, r, n, cx);
        } else {
            std::memcpy(dryL_, l, sizeof(float) * n);
            std::memcpy(dryR_, r, sizeof(float) * n);
            fx->process(l, r, n, cx);
            const float d = (target - in.fade) / (float)n;
            float f = in.fade;
            for (int i = 0; i < n; ++i) { f += d; l[i] = dryL_[i] + (l[i] - dryL_[i]) * f; r[i] = dryR_[i] + (r[i] - dryR_[i]) * f; }
            in.fade = target;
        }
        if (target > 0.f) lat += fx->latency();
    }
    c.latency.store(lat);

    // 4. mute -> send pre-fader -> fader -> send post-fader
    applyRamp(l, r, n, c.gMute, mT);
    c.gMute = mT;
    std::memcpy(c.keyL, l, sizeof(float) * n);
    std::memcpy(c.keyR, r, sizeof(float) * n);
    const bool canSend = c.type == CH_AUDIO || c.type == CH_AUX;
    auto doSends = [&](bool pre) {
        if (!canSend) return;
        for (auto& sd : c.sends) {
            const int d = sd.dest.load();
            const float tg = (d < 0 || sd.mute.load()) ? 0.f : faderLin(sd.levelDb.load());
            if (sd.pre.load() != pre) continue;
            if (d >= 0 && !(c.type == CH_AUDIO && c.inKind.load() == IN_BUS && (c.inIndex.load() & (kNumBuses - 1)) == d))
                addRamp(busCur_[d][0], busCur_[d][1], l, r, n, sd.gain, tg);
            sd.gain = tg;
        }
    };
    doSends(true);
    applyRamp(l, r, n, c.gFader, fT);
    c.gFader = fT;
    doSends(false);

    // 5. pan / balance
    {
        const float dl = (gl - c.gPanL) / (float)n, dr = (gr - c.gPanR) / (float)n;
        float a = c.gPanL, b = c.gPanR;
        for (int i = 0; i < n; ++i) { a += dl; b += dr; l[i] *= a; r[i] *= b; }
        c.gPanL = gl; c.gPanR = gr;
    }

    // 6. meter, key buffer
    if (!isMaster) {
        float pl = 0.f, pr = 0.f;
        for (int i = 0; i < n; ++i) { pl = std::max(pl, std::fabs(l[i])); pr = std::max(pr, std::fabs(r[i])); }
        atomicMax(c.peakL, pl);
        atomicMax(c.peakR, pr);
        std::memcpy(c.prevL, l, sizeof(float) * n);
        std::memcpy(c.prevR, r, sizeof(float) * n);
    }
}

void Mixer::renderChunk(const float* hw, int stride, float* out, int n) {
    // masukan hardware -> per kanal
    for (int ch = 0; ch < hwChN_; ++ch)
        for (int i = 0; i < n; ++i) hwIn_[ch][i] = hw[(size_t)i * stride + ch];
    renderPads(n);
    renderPlayer(n);
    {   // masukan aplikasi lain
        float tmp[kBlock * 2];
        const int sr10 = (int)(0.1f * sr_);
        if (appRing_.available() > sr10) appRing_.trimTo((int)(0.04f * sr_));
        const int got = appRing_.pop(tmp, n);
        for (int i = 0; i < n; ++i) { appL_[i] = i < got ? tmp[i * 2] : 0.f; appR_[i] = i < got ? tmp[i * 2 + 1] : 0.f; }
    }
    std::memcpy(busPrev_, busCur_, sizeof busCur_);
    std::memset(busCur_, 0, sizeof busCur_);
    std::memset(grp_, 0, sizeof grp_);
    std::memset(mst_, 0, sizeof mst_);

    bool anySolo = false;
    for (auto* c : audio_) if (c->solo.load()) { anySolo = true; break; }

    float l[kBlock], r[kBlock];
    for (auto* c : audio_) { gatherInput(*c, l, r, n); runChannel(*c, l, r, n, anySolo); route(*c, l, r, n); }
    for (auto* c : aux_) { gatherInput(*c, l, r, n); runChannel(*c, l, r, n, anySolo); route(*c, l, r, n); }
    for (auto* c : group_) { gatherInput(*c, l, r, n); runChannel(*c, l, r, n, anySolo); route(*c, l, r, n); }

    Channel& m = *master_;
    gatherInput(m, l, r, n);
    runChannel(m, l, r, n, anySolo);
    safety_.process(l, r, n, fxc_);
    if (masterMono_.load()) for (int i = 0; i < n; ++i) l[i] = r[i] = 0.5f * (l[i] + r[i]);
    float pl = 0.f, pr = 0.f;
    for (int i = 0; i < n; ++i) {
        l[i] = sanitize(l[i]); r[i] = sanitize(r[i]);
        out[i * 2] = l[i];
        out[i * 2 + 1] = r[i];
        pl = std::max(pl, std::fabs(l[i])); pr = std::max(pr, std::fabs(r[i]));
    }
    atomicMax(m.peakL, pl);
    atomicMax(m.peakR, pr);
    std::memcpy(m.prevL, l, sizeof(float) * n);
    std::memcpy(m.prevR, r, sizeof(float) * n);

    // Retrospective Record
    if (retroOn_.load(std::memory_order_relaxed)) {
        RetroBuf* rb = retro_.load(std::memory_order_acquire);
        if (rb) {
            uint64_t w = rb->written.load(std::memory_order_relaxed);
            for (int i = 0; i < n; ++i) {
                const size_t k = (size_t)((w + (uint64_t)i) % (uint64_t)rb->cap) * 2;
                rb->data[k] = toI16(l[i]);
                rb->data[k + 1] = toI16(r[i]);
            }
            rb->written.store(w + (uint64_t)n, std::memory_order_release);
        }
    }
    // tap rekam Player
    if (SpscRing* rr = rec_.load(std::memory_order_acquire)) {
        const int src = recSrc_.load();
        const float* sl = l; const float* sr = r;
        if (src >= 0 && src < kMaxChannels && chans_[src]) { sl = chans_[src]->prevL; sr = chans_[src]->prevR; }
        float tmp[kBlock * 2];
        for (int i = 0; i < n; ++i) { tmp[i * 2] = sl[i]; tmp[i * 2 + 1] = sr[i]; }
        if (rr->push(tmp, n) < n) recOver_.fetch_add(1);
    }
}

}  // namespace gp
