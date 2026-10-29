package com.bocbang.backend.stt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Behaviour every {@link SpeechToTextEngine} must keep so the shared runtime can drive it safely from many
 * threads. A new AI engine proves it fits by extending this class and returning its engine from {@link #engine()}.
 */
public abstract class SpeechToTextEngineContract {

    private static final int SAMPLE_RATE = 16000;

    protected abstract SpeechToTextEngine engine();

    @Test
    void hasAName() {
        assertThat(engine().name()).isNotBlank();
    }

    @Test
    void sustainsAtLeastOneStreamPerWorker() {
        assertThat(engine().sessionsPerWorker()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void finishAlwaysReturnsAFinalTranscript() {
        try (SpeechSession session = engine().openSession(SAMPLE_RATE)) {
            session.accept(silence(250), silence(250).length);

            TranscriptEvent result = session.finish();

            assertThat(result.type()).isEqualTo(TranscriptEvent.Type.FINAL);
            assertThat(result.text()).isNotNull();
        }
    }

    @Test
    void closingTwiceDoesNotFail() {
        SpeechSession session = engine().openSession(SAMPLE_RATE);

        session.close();

        assertThatCode(session::close).doesNotThrowAnyException();
    }

    @Test
    void servesSessionsOnDifferentThreadsAtTheSameTime() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(4);
        try {
            List<Future<TranscriptEvent>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(threads.submit(() -> {
                    try (SpeechSession session = engine().openSession(SAMPLE_RATE)) {
                        for (int chunk = 0; chunk < 8; chunk++) {
                            session.accept(silence(250), silence(250).length);
                        }
                        return session.finish();
                    }
                }));
            }
            for (Future<TranscriptEvent> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS).type()).isEqualTo(TranscriptEvent.Type.FINAL);
            }
        } finally {
            threads.shutdownNow();
        }
    }

    protected static byte[] silence(int milliseconds) {
        return new byte[SAMPLE_RATE * 2 * milliseconds / 1000];
    }
}
