package com.bocbang.backend.stt.jni;

import com.bocbang.backend.stt.SpeechSession;
import com.bocbang.backend.stt.TranscriptEvent;
import com.bocbang.backend.stt.Wav;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

/** Buffers one realtime utterance and runs the default JNI ASR model on {@link #finish()}. */
final class JniSpeechSession implements SpeechSession {

    private final JniSpeechEngine engine;
    private final int maxBytes;
    private final int maxSeconds;
    private final ByteArrayOutputStream utterance = new ByteArrayOutputStream();

    JniSpeechSession(JniSpeechEngine engine, int maxBytes, int maxSeconds) {
        this.engine = engine;
        this.maxBytes = maxBytes;
        this.maxSeconds = maxSeconds;
    }

    @Override
    public Optional<TranscriptEvent> accept(byte[] pcm, int length) {
        if (utterance.size() + length > maxBytes) {
            throw new IllegalArgumentException("Utterance is longer than the " + maxSeconds + " s limit");
        }
        utterance.write(pcm, 0, length);
        return Optional.empty();
    }

    @Override
    public TranscriptEvent finish() {
        byte[] bytes = utterance.toByteArray();
        utterance.reset();
        if (bytes.length == 0) {
            return TranscriptEvent.finalResult("");
        }
        return TranscriptEvent.finalResult(
                engine.transcribe(engine.defaultModel(), Wav.toFloats(bytes, bytes.length)));
    }

    @Override
    public void close() {
        utterance.reset();
    }
}
