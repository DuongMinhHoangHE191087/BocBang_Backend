package com.bocbang.backend.stt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Engine that returns a fixed transcript, so tests do not need a model on disk. */
public class FakeSpeechToTextEngine implements SpeechToTextEngine {

    private final String transcript;

    public final List<Integer> openedSampleRates = new ArrayList<>();
    public final List<String> requestedModels = new ArrayList<>();
    public final AtomicInteger receivedBytes = new AtomicInteger();
    public final AtomicInteger closedSessions = new AtomicInteger();
    public final AtomicInteger releasedCount = new AtomicInteger();

    public FakeSpeechToTextEngine(String transcript) {
        this.transcript = transcript;
    }

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public String defaultModel() {
        return "qwen3-asr";
    }

    @Override
    public List<String> models() {
        return List.of("qwen3-asr", "whisper-en");
    }

    @Override
    public SpeechSession openSession(int sampleRate) {
        openedSampleRates.add(sampleRate);
        return new SpeechSession() {
            @Override
            public Optional<TranscriptEvent> accept(byte[] pcm, int length) {
                receivedBytes.addAndGet(length);
                return Optional.of(TranscriptEvent.partial(transcript));
            }

            @Override
            public TranscriptEvent finish() {
                return TranscriptEvent.finalResult(transcript);
            }

            @Override
            public void close() {
                closedSessions.incrementAndGet();
            }
        };
    }

    @Override
    public String transcribe(String model, float[] samples) {
        requestedModels.add(model);
        return transcript;
    }

    @Override
    public void releaseModels() {
        releasedCount.incrementAndGet();
    }
}
