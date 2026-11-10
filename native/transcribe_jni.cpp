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
    std::vector<float> pcmScratch;
};

bool onAbort(void* userData) {
    return static_cast<TranscribeHandle*>(userData)->abort.load(std::memory_order_relaxed);
}

bool isPartialDecode(transcribe_status st) {
    return st == TRANSCRIBE_ERR_OUTPUT_TRUNCATED || st == TRANSCRIBE_ERR_OUTPUT_REPETITION;
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
        params.kv_type = TRANSCRIBE_KV_TYPE_F16;
        // 60 s of 16 kHz speech is ~750 audio tokens + prompt/text < 2048; bounds KV RAM on 4 GB boards.
        params.n_ctx = 2048;

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
        const float* samples = bocbang::copyFloatArray(env, pcm, n, handle->pcmScratch);

        const std::string lang = bocbang::toUtf8(env, language);
        const std::string initialPrompt = bocbang::toUtf8(env, prompt);

        transcribe_run_params params;
        transcribe_run_params_init(&params);
        params.timestamps = TRANSCRIBE_TIMESTAMPS_NONE;
        params.language = lang.empty() ? nullptr : lang.c_str();
        params.prompt = initialPrompt.empty() ? nullptr : initialPrompt.c_str();

        const transcribe_status st = transcribe_run(handle->session, samples, n, &params);
        const bool partial = isPartialDecode(st);
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

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_bocbang_backend_stt_jni_TranscribeNative_transcribeBatch(
        JNIEnv* env, jclass, jlong ptr, jobjectArray pcmBatch, jstring language, jstring prompt) {
    if (ptr == 0 || pcmBatch == nullptr) {
        bocbang::throwRecognitionException(env, "Qwen3-ASR session is not initialized");
        return nullptr;
    }
    auto* handle = reinterpret_cast<TranscribeHandle*>(ptr);
    handle->abort.store(false, std::memory_order_relaxed);

    try {
        const jsize batchSize = env->GetArrayLength(pcmBatch);
        jclass stringCls = env->FindClass("java/lang/String");
        if (stringCls == nullptr) {
            return nullptr;
        }
        if (batchSize <= 0) {
            return env->NewObjectArray(0, stringCls, nullptr);
        }

        std::vector<int> lengths(static_cast<size_t>(batchSize), 0);
        size_t totalSamples = 0;
        for (jsize i = 0; i < batchSize; ++i) {
            auto item = static_cast<jfloatArray>(env->GetObjectArrayElement(pcmBatch, i));
            if (item != nullptr) {
                const jsize len = env->GetArrayLength(item);
                if (len > 0) {
                    lengths[static_cast<size_t>(i)] = static_cast<int>(len);
                    totalSamples += static_cast<size_t>(len);
                }
                env->DeleteLocalRef(item);
            }
        }

        if (totalSamples > handle->pcmScratch.size()) {
            handle->pcmScratch.resize(totalSamples);
        }

        std::vector<const float*> ptrs(static_cast<size_t>(batchSize), nullptr);
        size_t cursor = 0;
        static const float kEmptySample = 0.0f;
        for (jsize i = 0; i < batchSize; ++i) {
            const int len = lengths[static_cast<size_t>(i)];
            if (len <= 0) {
                ptrs[static_cast<size_t>(i)] = &kEmptySample;
                continue;
            }
            auto item = static_cast<jfloatArray>(env->GetObjectArrayElement(pcmBatch, i));
            float* dst = handle->pcmScratch.data() + cursor;
            env->GetFloatArrayRegion(item, 0, len, dst);
            env->DeleteLocalRef(item);
            ptrs[static_cast<size_t>(i)] = dst;
            cursor += static_cast<size_t>(len);
        }

        const std::string lang = bocbang::toUtf8(env, language);
        const std::string initialPrompt = bocbang::toUtf8(env, prompt);

        transcribe_run_params params;
        transcribe_run_params_init(&params);
        params.timestamps = TRANSCRIBE_TIMESTAMPS_NONE;
        params.language = lang.empty() ? nullptr : lang.c_str();
        params.prompt = initialPrompt.empty() ? nullptr : initialPrompt.c_str();

        const transcribe_status st = transcribe_run_batch(
                handle->session, ptrs.data(), lengths.data(), static_cast<int>(batchSize), &params);
        if (st == TRANSCRIBE_ERR_ABORTED || handle->abort.load(std::memory_order_relaxed)) {
            bocbang::throwRecognitionException(env, "ABORTED");
            return nullptr;
        }
        if (st != TRANSCRIBE_OK && !isPartialDecode(st)) {
            bool fatal = true;
            if (transcribe_batch_n_results(handle->session) == batchSize) {
                fatal = false;
                for (jsize i = 0; i < batchSize; ++i) {
                    const transcribe_status itemSt = transcribe_batch_status(handle->session, i);
                    if (itemSt != TRANSCRIBE_OK && !isPartialDecode(itemSt)) {
                        fatal = true;
                        break;
                    }
                }
            }
            if (fatal) {
                bocbang::throwRecognitionException(env,
                        std::string("transcribe.cpp batch failed: ") + transcribe_status_string(st));
                return nullptr;
            }
        }

        jstring emptyStr = bocbang::toJString(env, "");
        jobjectArray out = env->NewObjectArray(batchSize, stringCls, emptyStr);
        if (out == nullptr) {
            return nullptr;
        }
        for (jsize i = 0; i < batchSize; ++i) {
            if (lengths[static_cast<size_t>(i)] <= 0) {
                continue;
            }
            const transcribe_status itemSt = transcribe_batch_status(handle->session, i);
            if (isPartialDecode(itemSt)) {
                BOCBANG_LOGW("bocbang-transcribe", "Batch item %d partial decode (%s)",
                        static_cast<int>(i), transcribe_status_string(itemSt));
            }
            const char* text = transcribe_batch_full_text(handle->session, i);
            if (text != nullptr && text[0] != '\0') {
                jstring jText = bocbang::toJString(env, text);
                env->SetObjectArrayElement(out, i, jText);
                env->DeleteLocalRef(jText);
            }
        }
        return out;
    } catch (const std::exception& e) {
        bocbang::throwRecognitionException(env, e.what());
    } catch (...) {
        bocbang::throwRecognitionException(env, "Unknown native error in transcribe.cpp batch");
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
