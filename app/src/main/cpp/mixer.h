// GP Mix (Android) - mesin mixer: graf channel, routing, send, solo/safe, Sample Pads,
// Player, Retrospective Record dan tap rekam. TANPA dependensi Android/Oboe (diuji di host).
//
// Threading:
//  - thread audio: render() saja. Memakai try_lock pada graphMu_; kalau UI sedang mengubah
//    struktur (microdetik) blok itu diam.
//  - thread lain: semua setter. Parameter = std::atomic; perubahan struktur memegang uiMu_ + graphMu_.
//  - data besar (pad, player, retro) diganti lewat pointer atomik; pointer lama dibebaskan setelah
//    thread audio melewati minimal dua render() berikutnya (quiesce).
#pragma once
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

#include "fx/fx_dyn.h"
#include "fx/fx_registry.h"

namespace gp {

constexpr int kMaxChannels = 64;
constexpr int kMaxInserts = 16;
constexpr int kMaxSends = 16;
constexpr int kNumBuses = 8;
constexpr int kNumGroups = 8;
constexpr int kNumPads = 16;
constexpr int kMaxHwIn = 8;

enum ChType { CH_AUDIO = 0, CH_AUX = 1, CH_GROUP = 2, CH_MASTER = 3 };
enum InKind { IN_NONE = 0, IN_HW = 1, IN_BUS = 2, IN_PADS = 3, IN_PLAYER = 4, IN_APP = 5 };
enum { SRC_PADS = 1, SRC_PLAYER = 2, SRC_APP = 4 };  // sumber tambahan di atas input utama

// Bit tujuan OUTPUT: bit0 = Master, bit1..8 = Group 1..8, bit9..16 = Bus 1..8.
constexpr uint32_t outMaster() { return 1u; }
constexpr uint32_t outGroup(int g) { return 1u << (1 + g); }
constexpr uint32_t outBus(int b) { return 1u << (9 + b); }

enum ChParam {
    CP_FADER_DB = 0, CP_PAN, CP_TRIM_DB, CP_MUTE, CP_SOLO, CP_SAFE, CP_POLARITY, CP_STEREO,
    CP_HPF_ON, CP_HPF_HZ, CP_HPF_SLOPE, CP_LPF_ON, CP_LPF_HZ, CP_LPF_SLOPE, CP_OUT_MASK, CP_GROUP_INDEX
};
enum PadParam { PP_GAIN_DB = 0, PP_START, PP_END, PP_CHOKE, PP_NORMALIZE };

// Ring buffer SPSC stereo (float interleaved). Satu produser, satu konsumen.
class SpscRing {
public:
    explicit SpscRing(int capacityFrames) {
        int c = 1;
        while (c < capacityFrames) c <<= 1;
        cap_ = c;
        buf_.assign((size_t)c * 2, 0.f);
    }
    int capacity() const { return cap_; }
    int available() const { return (int)(head_.load(std::memory_order_acquire) - tail_.load(std::memory_order_acquire)); }
    // Menulis sebanyak muat; kembalikan jumlah frame yang tertulis.
    int push(const float* interleaved, int frames) {
        const uint64_t h = head_.load(std::memory_order_relaxed), t = tail_.load(std::memory_order_acquire);
        const int space = cap_ - (int)(h - t);
        const int n = frames < space ? frames : space;
        for (int i = 0; i < n; ++i) {
            const size_t k = (size_t)((h + i) & (uint64_t)(cap_ - 1)) * 2;
            buf_[k] = interleaved[i * 2];
            buf_[k + 1] = interleaved[i * 2 + 1];
        }
        head_.store(h + n, std::memory_order_release);
        return n;
    }
    int pop(float* interleaved, int frames) {
        const uint64_t t = tail_.load(std::memory_order_relaxed), h = head_.load(std::memory_order_acquire);
        const int avail = (int)(h - t);
        const int n = frames < avail ? frames : avail;
        for (int i = 0; i < n; ++i) {
            const size_t k = (size_t)((t + i) & (uint64_t)(cap_ - 1)) * 2;
            interleaved[i * 2] = buf_[k];
            interleaved[i * 2 + 1] = buf_[k + 1];
        }
        tail_.store(t + n, std::memory_order_release);
        return n;
    }
    // Buang sampel lama sampai tersisa 'keep' frame (hanya dari sisi konsumen).
    void trimTo(int keep) {
        const uint64_t h = head_.load(std::memory_order_acquire), t = tail_.load(std::memory_order_relaxed);
        if ((int)(h - t) > keep) tail_.store(h - (uint64_t)keep, std::memory_order_release);
    }
private:
    int cap_;
    std::vector<float> buf_;
    std::atomic<uint64_t> head_{0}, tail_{0};
};

struct Insert {
    std::unique_ptr<Effect> fx;
    std::atomic<int> type{-1};
    std::atomic<bool> bypass{false};
    std::atomic<int> keySource{-1};
    float fade = 1.f;  // thread audio: 1 = efek aktif, 0 = bypass
};

struct Send {
    std::atomic<int> dest{-1};  // 0..7 = Bus; -1 = tidak ada
    std::atomic<float> levelDb{-60.f};
    std::atomic<bool> mute{false}, pre{false};
    float gain = 0.f;  // thread audio
};

struct Channel {
    int id = 0;
    int type = CH_AUDIO;
    std::atomic<float> faderDb{0.f}, pan{0.f}, trimDb{0.f};
    std::atomic<bool> mute{false}, solo{false}, safe{false}, polarity{false}, stereo{false};
    std::atomic<bool> hpfOn{false}, lpfOn{false};
    std::atomic<float> hpfHz{80.f}, lpfHz{16000.f};
    std::atomic<int> hpfSlope{0}, lpfSlope{0};
    std::atomic<uint32_t> outMask{1u};
    std::atomic<int> inKind{IN_NONE}, inIndex{0}, extraMask{0}, groupIndex{0};
    Insert inserts[kMaxInserts];
    Send sends[kMaxSends];
    // Meter & latency (dibaca UI)
    std::atomic<float> peakL{0.f}, peakR{0.f};
    std::atomic<int> latency{0};
    // State thread audio
    FilterCascade hpf, lpf;
    float lastHpf = -1.f, lastLpf = -1.f;
    int lastHpfSlope = -1, lastLpfSlope = -1;
    float gFader = 0.f, gMute = 1.f, gTrim = 1.f, gPanL = 1.f, gPanR = 1.f;
    bool gInit = false;
    float prevL[kBlock] = {}, prevR[kBlock] = {};  // keluaran akhir blok terakhir (sumber rekam)
    float keyL[kBlock] = {}, keyR[kBlock] = {};    // pasca-insert + mute, sebelum fader (sumber Key Source)
};

struct PadData {
    std::vector<float> L, R;
    float rate = 48000.f;
    float norm = 1.f;  // gain agar puncak = -1 dBFS
};
struct Pad {
    std::atomic<PadData*> data{nullptr};
    std::atomic<float> gainDb{0.f}, start{0.f}, end{1.f};
    std::atomic<int> choke{0};
    std::atomic<bool> normalize{false};
    std::atomic<float> vel{1.f};
    // thread audio
    PadData* v = nullptr;
    double pos = 0.0;
    bool active = false;
    int fadeLeft = -1;
};

struct PlayerData {
    std::vector<int16_t> pcm;  // stereo interleaved
    int frames = 0;
    int rate = 48000;
};
struct RetroBuf {
    std::vector<int16_t> data;  // stereo interleaved
    int cap = 0;
    std::atomic<uint64_t> written{0};
};

class Mixer {
public:
    Mixer();
    ~Mixer();

    void setSampleRate(float sr);
    float sampleRate() const { return sr_; }
    void setActive(bool a) { active_.store(a); }  // true bila thread audio berjalan

    // ---- struktur (thread UI) ----
    bool addChannel(int id, int type);
    bool removeChannel(int id);
    bool hasChannel(int id) const { return id >= 0 && id < kMaxChannels && chans_[id] != nullptr; }
    void setChannelParam(int id, int param, float v);
    void setInput(int id, int kind, int index, int extraMask);
    void setSend(int id, int slot, int dest, float levelDb, bool mute, bool pre);
    bool setInsert(int id, int slot, int type);  // type < 0 = kosongkan
    void setFxParam(int id, int slot, int idx, float v);
    void setInsertFlags(int id, int slot, bool bypass, int keySource);
    float fxGainReduction(int id, int slot);
    // ---- global ----
    void setTempo(float bpm) { bpm_.store(clampf(bpm, 20.f, 300.f)); }
    void setDuckingEnabled(bool on) { duck_.store(on); }
    void setPanLawDb(float db) { panLawDb_.store(clampf(db, -6.f, -3.f)); }
    void setMasterMono(bool m) { masterMono_.store(m); }

    // ---- pads ----
    void loadPad(int idx, const float* interleaved, int frames, int channels, int rate);
    void clearPad(int idx);
    void setPadParam(int idx, int param, float v);
    void setPadsMasterDb(float db) { padsMasterDb_.store(clampf(db, -60.f, 12.f)); }
    void triggerPad(int idx, float velocity);
    void stopPads() { stopMask_.fetch_or(0xFFFFu); }
    uint32_t padPlayingMask() const { return padPlaying_.load(); }

    // ---- player ----
    void loadPlayer(const int16_t* stereo, int frames, int rate);
    void clearPlayer();
    void playerPlay(bool on) { plyPlaying_.store(on); }
    void playerLoop(bool on) { plyLoop_.store(on); }
    void playerSeek(int64_t frame) { plySeek_.store(frame); }
    void playerVolumeDb(float db) { plyDb_.store(clampf(db, -60.f, 12.f)); }
    int64_t playerPos() const { return plyPosOut_.load(); }
    bool playerPlaying() const { return plyPlaying_.load(); }

    // ---- masukan aplikasi lain (AudioPlaybackCapture) ----
    int pushAppAudio(const float* stereoInterleaved, int frames) { return appRing_.push(stereoInterleaved, frames); }

    // ---- Retrospective Record (5 menit terakhir, int16 stereo) ----
    bool retroEnable(bool on);
    bool retroEnabled() const { return retroOn_.load(); }
    int retroBegin();  // bekukan titik awal; kembalikan jumlah frame tersedia
    int retroRead(int offsetFrames, int16_t* dst, int frames);
    int retroRate() const { return (int)sr_; }

    // ---- tap rekam Player (ke disk lewat thread Kotlin) ----
    bool recStart(int sourceChannel);  // -1 = Master
    void recStop();
    int recRead(float* dst, int maxFrames);
    int recOverruns() const { return recOver_.load(); }

    // ---- meter ----
    // out[0..2*kMaxChannels): peak L/R sejak pembacaan terakhir (di-reset);
    // out[2*kMaxChannels .. 3*kMaxChannels): latency ms. Ukuran = 3 * kMaxChannels.
    void readMeters(float* out);
    float padsPeak() { return padsPeak_.exchange(0.f); }

    // ---- audio ----
    // hw = input hardware interleaved (hwCh kanal, 'frames' frame) atau nullptr.
    // out = stereo interleaved.
    void render(const float* hw, int hwCh, float* out, int frames);

private:
    void renderChunk(const float* hw, int hwCh, float* out, int n);
    void renderPads(int n);
    void renderPlayer(int n);
    void gatherInput(Channel& c, float* l, float* r, int n);
    void runChannel(Channel& c, float* l, float* r, int n, bool anySolo);
    void route(Channel& c, const float* l, const float* r, int n);
    bool quiesce();  // true bila aman membebaskan pointer lama
    void rebuildLists();
    static void atomicMax(std::atomic<float>& a, float v) {
        float cur = a.load(std::memory_order_relaxed);
        while (v > cur && !a.compare_exchange_weak(cur, v, std::memory_order_relaxed)) {}
    }

    std::mutex uiMu_, graphMu_;
    Channel* chans_[kMaxChannels] = {};
    std::vector<Channel*> audio_, aux_, group_;
    Channel* master_ = nullptr;

    float sr_ = 48000.f;
    std::atomic<float> bpm_{120.f}, panLawDb_{-3.f}, padsMasterDb_{0.f}, plyDb_{0.f};
    std::atomic<bool> duck_{true}, masterMono_{false}, active_{false};
    std::atomic<uint32_t> epoch_{0};

    // buffer kerja (thread audio)
    float hwIn_[kMaxHwIn][kBlock];
    float padsL_[kBlock], padsR_[kBlock], plyL_[kBlock], plyR_[kBlock], appL_[kBlock], appR_[kBlock];
    float busCur_[kNumBuses][2][kBlock], busPrev_[kNumBuses][2][kBlock];
    float grp_[kNumGroups][2][kBlock], mst_[2][kBlock];
    float outL_[kBlock], outR_[kBlock], tmpL_[kBlock], tmpR_[kBlock], dryL_[kBlock], dryR_[kBlock];
    FxContext fxc_;
    Limiter safety_;
    int hwChN_ = 0;

    // pads
    Pad pads_[kNumPads];
    std::atomic<uint32_t> trigMask_{0}, stopMask_{0}, padPlaying_{0};
    std::atomic<float> padsPeak_{0.f};

    // player
    std::atomic<PlayerData*> player_{nullptr};
    PlayerData* plyV_ = nullptr;
    double plyPos_ = 0.0;
    std::atomic<bool> plyPlaying_{false}, plyLoop_{false};
    std::atomic<int64_t> plySeek_{-1}, plyPosOut_{0};

    // masukan aplikasi
    SpscRing appRing_;

    // retro
    std::mutex retroMu_;
    std::atomic<RetroBuf*> retro_{nullptr};
    std::atomic<bool> retroOn_{false};
    uint64_t retroBase_ = 0;
    int retroAvail_ = 0;

    // rec tap
    std::mutex recMu_;
    std::atomic<SpscRing*> rec_{nullptr};
    std::atomic<int> recSrc_{-1}, recOver_{0};
};

}  // namespace gp
