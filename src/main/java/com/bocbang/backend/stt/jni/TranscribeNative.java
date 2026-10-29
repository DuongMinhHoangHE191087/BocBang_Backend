package com.bocbang.backend.stt.jni;

/** JNI bindings for {@code libbocbang_transcribe.so} (transcribe.cpp — Qwen3-ASR GGUF). */
final class TranscribeNative {

    private TranscribeNative() {
    }

    static native long init(String modelPath, int threads);

    static native String transcribe(long handle, float[] pcm16k, String language, String prompt);

    static native void abort(long handle);

    static native void free(long handle);
}
