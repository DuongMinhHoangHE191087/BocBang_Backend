package com.bocbang.backend.stt;

/**
 * A speech model that serves streaming recognition sessions.
 * Implementations are shared by all connections and must be safe to call {@link #openSession(int)} concurrently.
 */
public interface SpeechToTextEngine {

    String name();

    /**
     * How many realtime streams one inference thread can keep up with. The default stream limit is the worker
     * count times this. Keep 1 for a model whose speed you have not measured; raise it only with numbers from
     * {@code tools/load-test.mjs} using real speech, because silence is about ten times cheaper to decode.
     */
    default int sessionsPerWorker() {
        return 1;
    }

    /** Opens a session for 16-bit little-endian mono PCM audio at the given sample rate. */
    SpeechSession openSession(int sampleRate);
}
