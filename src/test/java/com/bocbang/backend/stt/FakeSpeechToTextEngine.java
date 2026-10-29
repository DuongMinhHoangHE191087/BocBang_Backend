package com.bocbang.backend.stt;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Engine that returns a fixed transcript, so tests do not need a model on disk. */
public class FakeSpeechToTextEngine implements SpeechToTextEngine {

    private final String transcript;

    public final List<Integer> openedSampleRates = new CopyOnWriteArrayList<>();
    public final AtomicInteger receivedBytes = new AtomicInteger();
    public final AtomicInteger closedSessions = new AtomicInteger();

    public FakeSpeechToTextEngine(String transcript) {
        this.transcript = transcript;
    }

    @Override
    public String name() {
        return "fake";
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
}
