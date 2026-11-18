#include "audio_io.h"

#include <algorithm>
#include <cstring>

namespace gp {

namespace {
constexpr int kMaxSegment = 4096;

class DataCb : public oboe::AudioStreamDataCallback {
public:
    explicit DataCb(AudioIO* io) : io_(io) {}
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* data, int32_t n) override {
        return io_->onOutput(static_cast<float*>(data), n);
    }
private:
    AudioIO* io_;
};

class ErrCb : public oboe::AudioStreamErrorCallback {
public:
    explicit ErrCb(AudioIO* io) : io_(io) {}
    void onErrorAfterClose(oboe::AudioStream*, oboe::Result) override { io_->handleError(); }
private:
    AudioIO* io_;
};
}  // namespace

AudioIO::AudioIO(Mixer& mixer) : mixer_(mixer) {
    dataCb_ = std::make_shared<DataCb>(this);
    errCb_ = std::make_shared<ErrCb>(this);
}

int AudioIO::start(int inDev, int outDev, int inCh, int bufMult) {
    std::lock_guard<std::mutex> lk(mtx_);
    inDev_ = inDev;
    outDev_ = outDev;
    reqInCh_ = std::max(1, std::min(inCh, kMaxHwIn));
    bufMult_ = std::max(2, std::min(bufMult, 8));
    want_.store(true);
    return openLocked();
}

void AudioIO::stop() {
    std::lock_guard<std::mutex> lk(mtx_);
    want_.store(false);
    closeLocked();
}

void AudioIO::handleError() {
    // Dipanggil saat device dicabut / rute berubah (mis. headphone dilepas): buka ulang.
    std::unique_lock<std::mutex> lk(mtx_, std::try_to_lock);
    if (!lk.owns_lock() || !want_.load()) return;
    openLocked();
}

void AudioIO::closeLocked() {
    running_.store(false);
    mixer_.setActive(false);
    // Output ditutup dulu: setelah close() callback pasti sudah selesai.
    if (outStream_) { outStream_->requestStop(); outStream_->close(); outStream_.reset(); }
    if (inStream_) { inStream_->requestStop(); inStream_->close(); inStream_.reset(); }
}

int AudioIO::openLocked() {
    closeLocked();

    oboe::AudioStreamBuilder ob;
    ob.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setUsage(oboe::Usage::Media)
        ->setFormatConversionAllowed(true)
        ->setChannelConversionAllowed(true)
        ->setDataCallback(dataCb_)
        ->setErrorCallback(errCb_);
    if (outDev_ > 0) ob.setDeviceId(outDev_);
    if (ob.openStream(outStream_) != oboe::Result::OK) {
        // Coba lagi tanpa Exclusive.
        ob.setSharingMode(oboe::SharingMode::Shared);
        if (ob.openStream(outStream_) != oboe::Result::OK) { outStream_.reset(); return 1; }
    }
    sampleRate_ = outStream_->getSampleRate();
    burst_ = std::max(16, outStream_->getFramesPerBurst());
    outStream_->setBufferSizeInFrames(burst_ * bufMult_);

    // Input: coba jumlah kanal yang diminta, lalu 2, lalu 1; preset Unprocessed lalu VoiceRecognition.
    int rc = 2;
    inCh_ = 0;
    int tries[3] = {reqInCh_, 2, 1};
    for (int ti = 0; ti < 3 && rc != 0; ++ti) {
        if (ti > 0 && tries[ti] >= tries[ti - 1]) continue;
        for (int attempt = 0; attempt < 2 && rc != 0; ++attempt) {
            oboe::AudioStreamBuilder ib;
            ib.setDirection(oboe::Direction::Input)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(attempt == 0 ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(tries[ti])
                ->setSampleRate(sampleRate_)
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
                ->setInputPreset(attempt == 0 ? oboe::InputPreset::Unprocessed
                                              : oboe::InputPreset::VoiceRecognition)
                ->setFormatConversionAllowed(true)
                ->setChannelConversionAllowed(true)
                ->setErrorCallback(errCb_);
            if (inDev_ > 0) ib.setDeviceId(inDev_);
            if (ib.openStream(inStream_) == oboe::Result::OK) { rc = 0; inCh_ = inStream_->getChannelCount(); }
            else inStream_.reset();
        }
    }

    // Siapkan mixer dan FIFO sebelum stream berjalan.
    mixer_.setSampleRate((float)sampleRate_);
    fifoCap_ = 8192;
    const int ch = std::max(1, inCh_);
    fifo_.assign((size_t)fifoCap_ * ch, 0.f);
    hwTmp_.assign((size_t)kMaxSegment * ch, 0.f);
    fifoFrames_ = 0;
    mixer_.setActive(true);
    running_.store(true);

    if (inStream_) inStream_->requestStart();
    outStream_->requestStart();
    return rc;
}

oboe::DataCallbackResult AudioIO::onOutput(float* out, int32_t numFrames) {
    static thread_local bool ftz = false;
    if (!ftz) { enableFlushToZero(); ftz = true; }

    // 1. kuras semua input yang sudah tersedia ke FIFO (non-blocking)
    if (inStream_ && inCh_ > 0) {
        for (int guard = 0; guard < 8; ++guard) {
            const int space = fifoCap_ - fifoFrames_;
            if (space <= 0) break;
            const int want = std::min(space, 1024);
            auto r = inStream_->read(fifo_.data() + (size_t)fifoFrames_ * inCh_, want, 0);
            if (!r || r.value() <= 0) break;
            fifoFrames_ += r.value();
            if (r.value() < want) break;
        }
    }
    // 2. render per segmen
    int done = 0;
    while (done < numFrames) {
        const int n = std::min(kMaxSegment, numFrames - done);
        renderSegment(out + (size_t)done * 2, n);
        done += n;
    }
    // 3. jaga latency input tetap rendah bila FIFO menumpuk (drift clock)
    const int limit = std::max(burst_ * 3, numFrames * 2);
    if (inCh_ > 0 && fifoFrames_ > limit) {
        const int drop = fifoFrames_ - std::max(burst_, numFrames);
        std::memmove(fifo_.data(), fifo_.data() + (size_t)drop * inCh_, (size_t)(fifoFrames_ - drop) * inCh_ * sizeof(float));
        fifoFrames_ -= drop;
    }
    return oboe::DataCallbackResult::Continue;
}

void AudioIO::renderSegment(float* out, int n) {
    if (inCh_ <= 0) {
        mixer_.render(nullptr, 0, out, n);
        return;
    }
    const int ch = inCh_;
    const int have = std::min(fifoFrames_, n);
    // salin ke buffer sementara (nol di sisa bila underrun) lalu geser FIFO
    std::memcpy(hwTmp_.data(), fifo_.data(), (size_t)have * ch * sizeof(float));
    if (have < n) std::memset(hwTmp_.data() + (size_t)have * ch, 0, (size_t)(n - have) * ch * sizeof(float));
    if (have > 0) {
        const int remain = fifoFrames_ - have;
        if (remain > 0) std::memmove(fifo_.data(), fifo_.data() + (size_t)have * ch, (size_t)remain * ch * sizeof(float));
        fifoFrames_ = remain;
    }
    mixer_.render(hwTmp_.data(), ch, out, n);
}

void AudioIO::info(float* o) {
    std::unique_lock<std::mutex> lk(mtx_, std::try_to_lock);
    for (int i = 0; i < 7; ++i) o[i] = 0.f;
    o[6] = running_.load() ? 1.f : 0.f;
    if (!lk.owns_lock() || !outStream_) return;
    const float sr = (float)sampleRate_;
    o[0] = sr;
    o[1] = (float)burst_;
    o[2] = (float)outStream_->getBufferSizeInFrames();
    o[3] = (float)inCh_;
    const float inFrames = inStream_ ? (float)std::max(16, inStream_->getFramesPerBurst()) * 2.f : 0.f;
    o[4] = (o[2] + inFrames) * 1000.f / sr;
    auto x = outStream_->getXRunCount();
    o[5] = x ? (float)x.value() : 0.f;
}

}  // namespace gp
