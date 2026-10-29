#include <jni.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "jni_common.h"
#include "llama.h"

namespace {

struct LlamaHandle {
    llama_model* model = nullptr;
    std::atomic<bool> abort{false};
};

bool onLlamaAbort(void* userData) {
    return static_cast<LlamaHandle*>(userData)->abort.load(std::memory_order_relaxed);
}

void silentLlamaLog(enum ggml_log_level, const char*, void*) {
}

void setBatchTokens(llama_batch_ext* batch, const llama_token* tokens, int32_t nTokens, llama_pos pos0) {
    llama_batch_ext_clear(batch);
    for (int32_t i = 0; i < nTokens; ++i) {
        const int32_t idx = llama_batch_ext_add_token(batch, 0, tokens[i]);
        const llama_pos pos = pos0 + i;
        llama_batch_ext_set_pos(batch, idx, &pos);
    }
    llama_batch_ext_set_output_logits(batch, nTokens - 1, true);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_bocbang_backend_stt_jni_LlamaNative_init(
        JNIEnv* env, jclass, jstring modelPath) {
    llama_backend_init();
    llama_log_set(silentLlamaLog, nullptr);

    std::string path = bocbang::toUtf8(env, modelPath);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    llama_model* model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) {
        bocbang::throwRecognitionException(env, "llama.cpp failed to load model: " + path);
        return 0;
    }
    auto* handle = new LlamaHandle();
    handle->model = model;
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bocbang_backend_stt_jni_LlamaNative_abort(
        JNIEnv*, jclass, jlong ptr) {
    if (ptr != 0) {
        reinterpret_cast<LlamaHandle*>(ptr)->abort.store(true, std::memory_order_relaxed);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bocbang_backend_stt_jni_LlamaNative_generate(
        JNIEnv* env, jclass, jlong ptr, jstring promptStr, jint maxTokens, jint threads, jfloat temp) {
    if (ptr == 0 || promptStr == nullptr) {
        bocbang::throwRecognitionException(env, "Llama model is not initialized");
        return nullptr;
    }
    auto* handle = reinterpret_cast<LlamaHandle*>(ptr);
    handle->abort.store(false, std::memory_order_relaxed);

    const std::string prompt = bocbang::toUtf8(env, promptStr);
    if (prompt.empty()) {
        return bocbang::toJString(env, "");
    }

    const llama_vocab* vocab = llama_model_get_vocab(handle->model);
    const int nPrompt = -llama_tokenize(
            vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (nPrompt <= 0) {
        bocbang::throwRecognitionException(env, "Failed to tokenize refiner prompt");
        return nullptr;
    }

    std::vector<llama_token> promptTokens(static_cast<size_t>(nPrompt));
    if (llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
            promptTokens.data(), static_cast<int32_t>(promptTokens.size()), true, true) < 0) {
        bocbang::throwRecognitionException(env, "Failed to tokenize refiner prompt");
        return nullptr;
    }

    const int nPredict = maxTokens > 0 ? maxTokens : 1024;
    const int nThreads = threads > 0 ? threads : 4;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(std::max(2048, nPrompt + nPredict + 32));
    cparams.n_batch = static_cast<uint32_t>(std::max(512, nPrompt));
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreads;
    cparams.abort_callback = onLlamaAbort;
    cparams.abort_callback_data = handle;

    llama_context* ctx = llama_init_from_model(handle->model, cparams);
    if (ctx == nullptr) {
        bocbang::throwRecognitionException(env, "Failed to create llama_context");
        return nullptr;
    }

    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temp <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    llama_batch_ext* batch = llama_batch_ext_init(ctx);
    setBatchTokens(batch, promptTokens.data(), nPrompt, 0);

    std::string output;
    int nTokens = nPrompt;
    for (int pos = 0; pos + nTokens < nPrompt + nPredict; ) {
        int rc = llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch);
        if (handle->abort.load(std::memory_order_relaxed)) {
            llama_batch_ext_free(batch);
            llama_sampler_free(sampler);
            llama_free(ctx);
            bocbang::throwRecognitionException(env, "ABORTED");
            return nullptr;
        }
        if (rc != 0) {
            llama_batch_ext_free(batch);
            llama_sampler_free(sampler);
            llama_free(ctx);
            bocbang::throwRecognitionException(env, "llama_process failed with code " + std::to_string(rc));
            return nullptr;
        }
        pos += nTokens;

        llama_token next = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) {
            break;
        }
        char piece[256];
        int pieceLen = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, false);
        if (pieceLen > 0) {
            output.append(piece, static_cast<size_t>(pieceLen));
        }

        setBatchTokens(batch, &next, 1, pos);
        nTokens = 1;
    }

    llama_batch_ext_free(batch);
    llama_sampler_free(sampler);
    llama_free(ctx);
    return bocbang::toJString(env, output);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bocbang_backend_stt_jni_LlamaNative_free(
        JNIEnv*, jclass, jlong ptr) {
    if (ptr != 0) {
        auto* handle = reinterpret_cast<LlamaHandle*>(ptr);
        llama_model_free(handle->model);
        delete handle;
    }
}
