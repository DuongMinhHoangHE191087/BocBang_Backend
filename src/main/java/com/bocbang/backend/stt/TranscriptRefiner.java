package com.bocbang.backend.stt;

/** Cleans up raw ASR output (punctuation, casing, obvious recognition errors) without changing its meaning. */
public interface TranscriptRefiner {

    String refine(String transcript);

    /** Unloads any resident LLM weights from native memory. */
    default void releaseModel() {
    }
}
