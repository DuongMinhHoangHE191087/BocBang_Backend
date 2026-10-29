#include <jni.h>

#include <atomic>
#include <string>
#include <vector>

#include "jni_common.h"
#include "whisper.h"

namespace {

struct WhisperHandle {
    whisper_context* ctx = nullptr;
    std::atomic<bool> abort{false};
};

struct VadHandle {
    whisper_vad_context* ctx = nullptr;
    int threads = 4;
};

bool onWhisperAbort(void* userData) {
    return static_cast<WhisperHandle*>(userData)->abort.load(std::memory_order_relaxed);
}

void silentLog(enum ggml_log_level, const char*, void*) {
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_bocbang_backend_stt_jni_WhisperNative_whisperInit(
        JNIEnv* env, jclass, jstring modelPath) {
    whisper_log_set(silentLog, nullptr);
    std::string path = bocbang::toUtf8(env, modelPath);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context* ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) {
        bocbang::throwRecognitionException(env, "whisper.cpp failed to load model: " + path);
        return 0;
    }
    auto* handle = new WhisperHandle();
    handle->ctx = ctx;
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bocbang_backend_stt_jni_WhisperNative_whisperAbort(
        JNIEnv*, jclass, jlong ptr) {
    if (ptr != 0) {
        reinterpret_cast<WhisperHandle*>(ptr)->abort.store(true, std::memory_order_relaxed);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bocbang_backend_stt_jni_WhisperNative_whisperTranscribe(
        JNIEnv* env, jclass, jlong ptr, jfloatArray pcm, jint threads, jstring language) {
    if (ptr == 0 || pcm == nullptr) {
        bocbang::throwRecognitionException(env, "Whisper context is not initialized");
        return nullptr;
    }
    auto* handle = reinterpret_cast<WhisperHandle*>(ptr);
    handle->abort.store(false, std::memory_order_relaxed);

    const jsize n = env->GetArrayLength(pcm);
    if (n <= 0) {
        return bocbang::toJString(env, "");
    }
    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());

    std::string lang = bocbang::toUtf8(env, language);
    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.n_threads = threads > 0 ? threads : 4;
    wparams.print_progress = false;
    wparams.print_realtime = false;
    wparams.print_special = false;
    wparams.print_timestamps = false;
    wparams.no_timestamps = true;
    wparams.single_segment = false;
    wparams.language = lang.empty() ? "en" : lang.c_str();
    wparams.abort_callback = onWhisperAbort;
    wparams.abort_callback_user_data = handle;

    int rc = whisper_full(handle->ctx, wparams, samples.data(), n);
    if (handle->abort.load(std::memory_order_relaxed)) {
        bocbang::throwRecognitionException(env, "ABORTED");
        return nullptr;
    }
    if (rc != 0) {
        bocbang::throwRecognitionException(env, "whisper_full failed with code " + std::to_string(rc));
        return nullptr;
    }

    std::string out;
    const int nSegments = whisper_full_n_segments(handle->ctx);
    for (int i = 0; i < nSegments; ++i) {
        const char* seg = whisper_full_get_segment_text(handle->ctx, i);
        if (seg != nullptr) {
            out += seg;
        }
    }
    return bocbang::toJString(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bocbang_backend_stt_jni_WhisperNative_whisperFree(
        JNIEnv*, jclass, jlong ptr) {
    if (ptr != 0) {
        auto* handle = reinterpret_cast<WhisperHandle*>(ptr);
        whisper_free(handle->ctx);
        delete handle;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bocbang_backend_stt_jni_WhisperNative_vadInit(
        JNIEnv* env, jclass, jstring modelPath, jint threads) {
    whisper_log_set(silentLog, nullptr);
    std::string path = bocbang::toUtf8(env, modelPath);
    whisper_vad_context_params ctxParams = whisper_vad_default_context_params();
    ctxParams.n_threads = threads > 0 ? threads : 4;
    ctxParams.use_gpu = false;

    whisper_vad_context* vctx = whisper_vad_init_from_file_with_params(path.c_str(), ctxParams);
    if (vctx == nullptr) {
        bocbang::throwRecognitionException(env, "Silero VAD failed to load model: " + path);
        return 0;
    }
    auto* handle = new VadHandle();
    handle->ctx = vctx;
    handle->threads = ctxParams.n_threads;
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_bocbang_backend_stt_jni_WhisperNative_vadDetect(
        JNIEnv* env, jclass, jlong ptr, jfloatArray pcm,
        jfloat threshold, jint minSpeechMs, jint minSilenceMs, jfloat maxSpeechSec, jint speechPadMs) {
    if (ptr == 0 || pcm == nullptr) {
        bocbang::throwRecognitionException(env, "Silero VAD context is not initialized");
        return nullptr;
    }
    auto* handle = reinterpret_cast<VadHandle*>(ptr);
    const jsize n = env->GetArrayLength(pcm);
    if (n <= 0) {
        return env->NewDoubleArray(0);
    }
    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());

    whisper_vad_params params = whisper_vad_default_params();
    params.threshold = threshold;
    params.min_speech_duration_ms = minSpeechMs;
    params.min_silence_duration_ms = minSilenceMs;
    params.max_speech_duration_s = maxSpeechSec;
    params.speech_pad_ms = speechPadMs;

    whisper_vad_segments* segments = whisper_vad_segments_from_samples(
            handle->ctx, params, samples.data(), n);
    if (segments == nullptr) {
        bocbang::throwRecognitionException(env, "whisper_vad_segments_from_samples failed");
        return nullptr;
    }

    const int count = whisper_vad_segments_n_segments(segments);
    std::vector<jdouble> bounds(static_cast<size_t>(count) * 2);
    for (int i = 0; i < count; ++i) {
        // whisper.cpp returns VAD segment timestamps in centiseconds (1/100 s).
        bounds[static_cast<size_t>(i) * 2] =
                static_cast<jdouble>(whisper_vad_segments_get_segment_t0(segments, i)) * 0.01;
        bounds[static_cast<size_t>(i) * 2 + 1] =
                static_cast<jdouble>(whisper_vad_segments_get_segment_t1(segments, i)) * 0.01;
    }
    whisper_vad_free_segments(segments);

    jdoubleArray out = env->NewDoubleArray(count * 2);
    if (out != nullptr && count > 0) {
        env->SetDoubleArrayRegion(out, 0, count * 2, bounds.data());
    }
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bocbang_backend_stt_jni_WhisperNative_vadFree(
        JNIEnv*, jclass, jlong ptr) {
    if (ptr != 0) {
        auto* handle = reinterpret_cast<VadHandle*>(ptr);
        whisper_vad_free(handle->ctx);
        delete handle;
    }
}
