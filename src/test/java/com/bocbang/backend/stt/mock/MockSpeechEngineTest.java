package com.bocbang.backend.stt.mock;

import static org.assertj.core.api.Assertions.assertThat;

import com.bocbang.backend.TestSettings;
import com.bocbang.backend.stt.SpeechSession;
import com.bocbang.backend.stt.SpeechToTextEngine;
import com.bocbang.backend.stt.SpeechToTextEngineContract;
import com.bocbang.backend.stt.TranscriptEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MockSpeechEngineTest extends SpeechToTextEngineContract {

    private static final int HALF_SECOND = 16000;

    @Override
    protected SpeechToTextEngine engine() {
        return new MockSpeechEngine(TestSettings.runtime("mock.segment", "1s"));
    }

    @Test
    void growsAPartialSentenceThenCommitsItAsFinalAtTheEndOfTheSegment() {
        MockSpeechEngine engine = new MockSpeechEngine(TestSettings.runtime("mock.segment", "1s"));
        List<TranscriptEvent> events = new ArrayList<>();
        try (SpeechSession session = engine.openSession(16000)) {
            for (int chunk = 0; chunk < 4; chunk++) {
                Optional<TranscriptEvent> event = session.accept(new byte[HALF_SECOND / 2], HALF_SECOND / 2);
                event.ifPresent(events::add);
            }
        }

        List<TranscriptEvent> partials = events.stream()
                .filter(event -> event.type() == TranscriptEvent.Type.PARTIAL)
                .toList();
        TranscriptEvent firstFinal = events.stream()
                .filter(event -> event.type() == TranscriptEvent.Type.FINAL)
                .findFirst()
                .orElseThrow();

        assertThat(partials).isNotEmpty();
        assertThat(partials).allSatisfy(partial -> assertThat(firstFinal.text()).startsWith(partial.text()));
        assertThat(partials.get(0).text().length())
                .isLessThan(partials.get(partials.size() - 1).text().length());
        assertThat(firstFinal.text()).isNotBlank();
    }

    @Test
    void isCheapEnoughToShowSeveralStreamsPerWorker() {
        assertThat(engine().sessionsPerWorker()).isGreaterThan(1);
    }

    @Test
    void reportsAPartialOnlyWhenItsTextChanged() {
        MockSpeechEngine engine = new MockSpeechEngine(TestSettings.runtime("mock.segment", "10s"));
        try (SpeechSession session = engine.openSession(16000)) {
            session.accept(new byte[16000 * 2 / 4], 16000 * 2 / 4);

            Optional<TranscriptEvent> sameWordStillSpeaking = session.accept(new byte[10], 10);

            assertThat(sameWordStillSpeaking).isEmpty();
        }
    }

    @Test
    void simulatesProcessingTimePerChunkWhenConfigured() {
        MockSpeechEngine engine = new MockSpeechEngine(TestSettings.runtime("mock.chunk-latency", "40ms"));
        try (SpeechSession session = engine.openSession(16000)) {
            long started = System.nanoTime();

            session.accept(new byte[3200], 3200);

            assertThat((System.nanoTime() - started) / 1_000_000).isGreaterThanOrEqualTo(40);
        }
    }
}
