package com.bocbang.backend.stt.jni;

/** JNI bindings for {@code libbocbang_whisper.so} (whisper.cpp — Whisper ASR + Silero VAD). */
final class WhisperNative {

    private WhisperNative() {
    }

    static native long whisperInit(String modelPath);

    static native String whisperTranscribe(long handle, float[] pcm16k, int threads, String language);

    static native void whisperAbort(long handle);

    static native void whisperFree(long handle);

    static native long vadInit(String modelPath, int threads);

    /** Returns flat {@code [start0, end0, start1, end1, ...]} timestamps in seconds. */
    static native double[] vadDetect(
            long handle,
            float[] pcm16k,
            float threshold,
            int minSpeechMs,
            int minSilenceMs,
            float maxSpeechSec,
            int speechPadMs);

    static native void vadFree(long handle);
}
