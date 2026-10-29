package com.bocbang.backend.stt;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Speech models shared by all connections and jobs. Implementations must be safe to call concurrently.
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

    /** Model used by realtime sessions and by jobs that do not choose one. */
    String defaultModel();

    List<String> models();

    /**
     * Opens a realtime session on the default model for 16-bit little-endian mono PCM.
     *
     * @throws IllegalArgumentException if the engine does not accept this sample rate
     */
    SpeechSession openSession(int sampleRate);

    /**
     * Transcribes normalized 16 kHz mono float samples in {@code [-1.0, 1.0)} with the named model.
     *
     * @throws IllegalArgumentException if the model is unknown
     * @throws SpeechRecognitionException if the model fails
     */
    String transcribe(String model, float[] samples);

    /** Transcribes one complete 16-bit mono WAV file at the engine's sample rate. */
    default String transcribe(String model, Path wav) {
        try {
            return transcribe(model, Wav.readAllFloats(wav));
        } catch (IOException e) {
            throw new SpeechRecognitionException("Failed to read WAV audio: " + wav.getFileName(), e);
        }
    }

    /** Unloads any resident ASR models from native memory so the LLM refiner can use that RAM. */
    default void releaseModels() {
    }
}
