package com.bocbang.backend.stt.jni;

/** JNI bindings for {@code libbocbang_llama.so} (llama.cpp — Qwen3-4B-Instruct GGUF). */
final class LlamaNative {

    private LlamaNative() {
    }

    static native long init(String modelPath);

    static native String generate(long handle, String prompt, int maxTokens, int threads, float temp);

    static native void abort(long handle);

    static native void free(long handle);
}
