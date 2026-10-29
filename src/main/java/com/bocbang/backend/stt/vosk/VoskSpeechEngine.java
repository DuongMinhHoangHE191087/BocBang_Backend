package com.bocbang.backend.stt.vosk;

import com.bocbang.backend.stt.SpeechSession;
import com.bocbang.backend.stt.SpeechToTextEngine;
import com.bocbang.backend.stt.SttProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

/** Offline CPU speech recognition with Vosk. The loaded model is shared; each session owns a Recognizer. */
@Component
@ConditionalOnProperty(prefix = "stt", name = "engine", havingValue = "vosk", matchIfMissing = true)
public class VoskSpeechEngine implements SpeechToTextEngine, AutoCloseable {

    private final Model model;

    public VoskSpeechEngine(SttProperties properties) throws IOException {
        Path modelPath = Path.of(properties.vosk().modelPath());
        if (!Files.isDirectory(modelPath)) {
            throw new IllegalStateException("Vosk model not found at " + modelPath.toAbsolutePath()
                    + ". Download a model from https://alphacephei.com/vosk/models and unpack it there.");
        }
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        this.model = new Model(modelPath.toString());
    }

    @Override
    public String name() {
        return "vosk";
    }

    /**
     * Measured with the small Vietnamese model on continuous speech: about 0.6 to 1.1 core per stream, so one
     * stream per worker is the most this can promise. Quieter or in-domain audio is cheaper; raise
     * {@code stt.pool.max-sessions} after measuring your own traffic.
     */
    @Override
    public int sessionsPerWorker() {
        return 1;
    }

    @Override
    public SpeechSession openSession(int sampleRate) {
        try {
            return new VoskSpeechSession(new Recognizer(model, sampleRate));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create Vosk recognizer", e);
        }
    }

    @Override
    public void close() {
        model.close();
    }
}
