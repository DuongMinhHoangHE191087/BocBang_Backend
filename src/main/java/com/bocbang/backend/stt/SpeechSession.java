package com.bocbang.backend.stt;

import java.util.Optional;

/**
 * One streaming recognition session (one WebSocket connection or one file). Not thread-safe:
 * feed it from one thread at a time.
 */
public interface SpeechSession extends AutoCloseable {

    /**
     * Feeds the first {@code length} bytes of {@code pcm} (16-bit little-endian mono).
     * Returns a transcript update when the model has something new to report.
     */
    Optional<TranscriptEvent> accept(byte[] pcm, int length);

    /** Ends the current utterance and returns its final transcript. The session can keep receiving audio. */
    TranscriptEvent finish();

    @Override
    void close();
}
