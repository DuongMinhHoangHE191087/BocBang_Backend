package com.bocbang.backend.stt.vosk;

import com.bocbang.backend.stt.SpeechSession;
import com.bocbang.backend.stt.TranscriptEvent;
import java.util.Optional;
import org.vosk.Recognizer;
import tools.jackson.databind.ObjectMapper;

/** Adapts a Vosk {@link Recognizer} to {@link SpeechSession}, emitting partial results only when they change. */
final class VoskSpeechSession implements SpeechSession {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Recognizer recognizer;
    private String lastPartial = "";

    VoskSpeechSession(Recognizer recognizer) {
        this.recognizer = recognizer;
    }

    @Override
    public Optional<TranscriptEvent> accept(byte[] pcm, int length) {
        if (recognizer.acceptWaveForm(pcm, length)) {
            // Vosk detected the end of an utterance; its result is {"text": "..."}.
            lastPartial = "";
            return Optional.of(TranscriptEvent.finalResult(textOf(recognizer.getResult())));
        }
        // Partial result is {"partial": "..."}, empty while the model is still silent.
        String partial = JSON.readTree(recognizer.getPartialResult()).path("partial").asString("");
        if (partial.equals(lastPartial)) {
            return Optional.empty();
        }
        lastPartial = partial;
        return Optional.of(TranscriptEvent.partial(partial));
    }

    @Override
    public TranscriptEvent finish() {
        lastPartial = "";
        return TranscriptEvent.finalResult(textOf(recognizer.getFinalResult()));
    }

    @Override
    public void close() {
        recognizer.close();
    }

    private static String textOf(String result) {
        return JSON.readTree(result).path("text").asString("");
    }
}
