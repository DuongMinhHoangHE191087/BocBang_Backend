package com.bocbang.backend.stt;

/**
 * A speech model that serves streaming recognition sessions.
 * Implementations are shared by all connections and must be safe to call {@link #openSession(int)} concurrently.
 */
public interface SpeechToTextEngine {

    String name();

    /** Opens a session for 16-bit little-endian mono PCM audio at the given sample rate. */
    SpeechSession openSession(int sampleRate);
}
