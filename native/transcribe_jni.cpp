#include <jni.h>

#include <atomic>
#include <string>
#include <vector>

#include "jni_common.h"
#include "transcribe.h"

namespace {

struct TranscribeHandle {
    transcribe_session* session = nullptr;
    std::atomic<bool> abort{false};
};

bool onAbort(void* userData) {
    return static_cast<TranscribeHandle*>(userData)->abort.load(std::memory_order_relaxed);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_bocbang_backend_stt_jni_TranscribeNative_init(
        JNIEnv* env, jclass, jstring modelPath, jint threads) {
    try {
        std::string path = bocbang::toUtf8(env, modelPath);
        transcribe_session_params params;
        transcribe_session_params_init(&params);
        params.n_threads = threads > 0 ? threads : 4;

        auto* handle = new TranscribeHandle();
        transcribe_status st = transcribe_open(path.c_str(), nullptr, &params, &handle->session);
        if (st != TRANSCRIBE_OK || handle->session == nullptr) {
            std::string msg = std::string("transcribe.cpp failed to load model: ")
                    + transcribe_status_string(st);
            delete handle;
            bocbang::throwRecognitionException(env, msg);
            return 0;
        }
        transcribe_set_abort_callback(handle->session, onAbort, handle);
        return reinterpret_cast<jlong>(handle);
    } catch (const std::exception& e) {
        bocbang::throwRecognitionException(env, e.what());
    } catch (...) {
        bocbang::throwRecognitionException(env, "Unknown native error loading transcribe.cpp model");
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bocbang_backend_stt_jni_TranscribeNative_abort(
        JNIEnv*, jclass, jlong ptr) {
    if (ptr != 0) {
        reinterpret_cast<TranscribeHandle*>(ptr)->abort.store(true, std::memory_order_relaxed);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bocbang_backend_stt_jni_TranscribeNative_transcribe(
        JNIEnv* env, jclass, jlong ptr, jfloatArray pcm, jstring language, jstring prompt) {
    if (ptr == 0 || pcm == nullptr) {
        bocbang::throwRecognitionException(env, "Qwen3-ASR session is not initialized");
        return nullptr;
    }
    auto* handle = reinterpret_cast<TranscribeHandle*>(ptr);
    handle->abort.store(false, std::memory_order_relaxed);

    try {
        const jsize n = env->GetArrayLength(pcm);
        if (n <= 0) {
            return bocbang::toJString(env, "");
        }
        std::vector<float> samples(static_cast<size_t>(n));
        env->GetFloatArrayRegion(pcm, 0, n, samples.data());

        const std::string lang = bocbang::toUtf8(env, language);
        const std::string initialPrompt = bocbang::toUtf8(env, prompt);

        transcribe_run_params params;
        transcribe_run_params_init(&params);
        params.language = lang.empty() ? nullptr : lang.c_str();
        params.prompt = initialPrompt.empty() ? nullptr : initialPrompt.c_str();

        const transcribe_status st = transcribe_run(handle->session, samples.data(), n, &params);
        const bool partial = st == TRANSCRIBE_ERR_OUTPUT_TRUNCATED
                || st == TRANSCRIBE_ERR_OUTPUT_REPETITION;
        if (partial) {
            BOCBANG_LOGW("bocbang-transcribe", "Partial decode (%s), returning decoded text",
                    transcribe_status_string(st));
        }
        if (st != TRANSCRIBE_OK && !partial) {
            if (st == TRANSCRIBE_ERR_ABORTED || handle->abort.load(std::memory_order_relaxed)) {
                bocbang::throwRecognitionException(env, "ABORTED");
            } else {
                bocbang::throwRecognitionException(env,
                        std::string("transcribe.cpp failed: ") + transcribe_status_string(st));
            }
            return nullptr;
        }
        const char* text = transcribe_full_text(handle->session);
        return bocbang::toJString(env, text == nullptr ? "" : text);
    } catch (const std::exception& e) {
        bocbang::throwRecognitionException(env, e.what());
    } catch (...) {
        bocbang::throwRecognitionException(env, "Unknown native error in transcribe.cpp");
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bocbang_backend_stt_jni_TranscribeNative_free(
        JNIEnv*, jclass, jlong ptr) {
    if (ptr != 0) {
        auto* handle = reinterpret_cast<TranscribeHandle*>(ptr);
        transcribe_close(handle->session);
        delete handle;
    }
}
