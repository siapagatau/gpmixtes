// GP Mix (Android) - lapisan I/O audio memakai Oboe: satu stream output (callback) dan satu stream input
// (dibaca non-blocking di dalam callback output, lewat FIFO kecil supaya tahan selisih clock).
#pragma once
#include <oboe/Oboe.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <vector>

#include "mixer.h"

namespace gp {

class AudioIO {
public:
    explicit AudioIO(Mixer& mixer);

    // 0 = ok; 1 = output gagal dibuka; 2 = output ok tetapi input gagal (mixer tetap jalan tanpa mic).
    int start(int inputDeviceId, int outputDeviceId, int inputChannels, int bufferMultiplier);
    void stop();
    bool running() const { return running_.load(); }

    // [sampleRate, framesPerBurst, bufferFrames, inputChannels, latencyMs, xruns, running]
    void info(float* out7);

    // Dipanggil dari callback Oboe.
    oboe::DataCallbackResult onOutput(float* out, int32_t numFrames);
    void handleError();

private:
    int openLocked();
    void closeLocked();
    void renderSegment(float* out, int n);

    Mixer& mixer_;
    std::mutex mtx_;
    std::shared_ptr<oboe::AudioStream> outStream_, inStream_;
    std::shared_ptr<oboe::AudioStreamDataCallback> dataCb_;
    std::shared_ptr<oboe::AudioStreamErrorCallback> errCb_;
    std::atomic<bool> running_{false}, want_{false};

    int inDev_ = 0, outDev_ = 0, reqInCh_ = 1, bufMult_ = 3;
    int sampleRate_ = 48000, inCh_ = 0, burst_ = 192;

    // FIFO input (hanya disentuh thread audio, kecuali saat open/close di bawah mtx_ dengan stream berhenti)
    std::vector<float> fifo_, hwTmp_;
    int fifoFrames_ = 0, fifoCap_ = 0;
};

}  // namespace gp
