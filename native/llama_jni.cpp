#include <jni.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "jni_common.h"
#include "llama.h"

namespace {

struct LlamaHandle {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_batch_ext* batch = nullptr;
    uint32_t ctxCap = 0;
    uint32_t batchCap = 0;
    std::vector<llama_token> kvTokens;
    std::atomic<bool> abort{false};

    void resetContext() {
        if (batch != nullptr) {
            llama_batch_ext_free(batch);
            batch = nullptr;
        }
        if (ctx != nullptr) {
            llama_free(ctx);
            ctx = nullptr;
        }
        ctxCap = 0;
        batchCap = 0;
        kvTokens.clear();
    }

    void invalidateKvCache() {
        if (ctx != nullptr) {
            llama_memory_t mem = llama_get_memory(ctx);
            if (mem != nullptr) {
                llama_memory_clear(mem, false);
            }
        }
        kvTokens.clear();
    }
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

int chooseBatchThreads(int decodeThreads) {
    const unsigned int hw = std::thread::hardware_concurrency();
    const int maxHw = hw > 0 ? static_cast<int>(hw) : 8;
    // Prefill is compute-bound GEMM and scales onto extra LITTLE cores (e.g. 6 of 8 on G520),
    // while single-token decode stays on decodeThreads to avoid barrier straggler overhead.
    return std::min(maxHw, std::max(decodeThreads, 6));
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
    mparams.use_extra_bufts = true;

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

    // Transcript cleanup output length tracks input length closely; capping prevents oversized KV
    // allocations and runaway generation loops while respecting explicit caller limits.
    const int callerCap = maxTokens > 0 ? maxTokens : 1024;
    const int nPredict = std::min(callerCap, std::max(256, nPrompt * 2));
    const int nThreads = threads > 0 ? threads : 4;
    const int nThreadsBatch = chooseBatchThreads(nThreads);

    const uint32_t neededCtx = static_cast<uint32_t>(std::max(2048, nPrompt + nPredict + 32));
    const uint32_t neededBatch = static_cast<uint32_t>(std::max(1024, nPrompt));

    if (handle->ctx == nullptr || neededCtx > handle->ctxCap || neededBatch > handle->batchCap) {
        handle->resetContext();

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = neededCtx;
        cparams.n_batch = neededBatch;
        cparams.n_ubatch = std::min(neededBatch, 512u);
        cparams.n_threads = nThreads;
        cparams.n_threads_batch = nThreadsBatch;
        cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
        cparams.type_k = GGML_TYPE_F16;
        cparams.type_v = GGML_TYPE_F16;
        cparams.no_perf = true;
        cparams.abort_callback = onLlamaAbort;
        cparams.abort_callback_data = handle;

        handle->ctx = llama_init_from_model(handle->model, cparams);
        if (handle->ctx == nullptr) {
            bocbang::throwRecognitionException(env, "Failed to create llama_context");
            return nullptr;
        }
        handle->batch = llama_batch_ext_init(handle->ctx);
        if (handle->batch == nullptr) {
            handle->resetContext();
            bocbang::throwRecognitionException(env, "Failed to create llama_batch_ext");
            return nullptr;
        }
        handle->ctxCap = neededCtx;
        handle->batchCap = neededBatch;
    } else {
        llama_set_n_threads(handle->ctx, nThreads, nThreadsBatch);
    }

    // Reuse KV cache entries for the shared system-prompt prefix across consecutive batches,
    // keeping at least the last prompt token so llama_process computes fresh output logits.
    size_t commonLen = 0;
    const size_t maxPrefix = static_cast<size_t>(nPrompt - 1);
    while (commonLen < handle->kvTokens.size()
            && commonLen < maxPrefix
            && handle->kvTokens[commonLen] == promptTokens[commonLen]) {
        ++commonLen;
    }
    llama_memory_t mem = llama_get_memory(handle->ctx);
    if (mem != nullptr) {
        if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(commonLen), -1)) {
            llama_memory_clear(mem, false);
            commonLen = 0;
        }
    } else {
        commonLen = 0;
    }
    handle->kvTokens.resize(commonLen);

    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temp <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    const int32_t prefillCount = nPrompt - static_cast<int32_t>(commonLen);
    setBatchTokens(handle->batch,
            promptTokens.data() + commonLen,
            prefillCount,
            static_cast<llama_pos>(commonLen));

    std::string output;
    output.reserve(static_cast<size_t>(nPrompt) * 4);
    bool prefilled = false;
    int nTokens = prefillCount;
    for (int pos = static_cast<int>(commonLen); pos + nTokens < nPrompt + nPredict; ) {
        int rc = llama_process(handle->ctx, LLAMA_PROCESS_TYPE_DECODE, handle->batch);
        if (handle->abort.load(std::memory_order_relaxed)) {
            llama_sampler_free(sampler);
            handle->invalidateKvCache();
            bocbang::throwRecognitionException(env, "ABORTED");
            return nullptr;
        }
        if (rc != 0) {
            llama_sampler_free(sampler);
            handle->invalidateKvCache();
            bocbang::throwRecognitionException(env, "llama_process failed with code " + std::to_string(rc));
            return nullptr;
        }
        if (!prefilled) {
            handle->kvTokens.insert(handle->kvTokens.end(),
                    promptTokens.begin() + static_cast<std::ptrdiff_t>(commonLen),
                    promptTokens.end());
            prefilled = true;
        }
        pos += nTokens;

        llama_token next = llama_sampler_sample(sampler, handle->ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) {
            break;
        }
        char piece[256];
        int pieceLen = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, false);
        if (pieceLen > 0) {
            output.append(piece, static_cast<size_t>(pieceLen));
        }

        handle->kvTokens.push_back(next);
        setBatchTokens(handle->batch, &next, 1, pos);
        nTokens = 1;
    }

    llama_sampler_free(sampler);
    return bocbang::toJString(env, output);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bocbang_backend_stt_jni_LlamaNative_free(
        JNIEnv*, jclass, jlong ptr) {
    if (ptr != 0) {
        auto* handle = reinterpret_cast<LlamaHandle*>(ptr);
        handle->resetContext();
        llama_model_free(handle->model);
        delete handle;
    }
}
