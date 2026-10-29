package com.bocbang.backend.stt.mock;

import com.bocbang.backend.stt.SpeechSession;
import com.bocbang.backend.stt.SpeechToTextEngine;
import com.bocbang.backend.stt.SttProperties;
import com.bocbang.backend.stt.TranscriptEvent;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stand-in for a real model ({@code stt.engine=mock}): ignores the audio content and "recognizes" a scripted
 * sentence one word per quarter second of audio. It lets the demo page and load tests run without a model, and
 * is the smallest example of an engine for people adding a real one.
 */
@Component
@ConditionalOnProperty(prefix = "stt", name = "engine", havingValue = "mock")
public class MockSpeechEngine implements SpeechToTextEngine {

    private static final List<String> SENTENCES = List.of(
            "xin chào đây là bản chạy thử của máy chủ nhận dạng giọng nói",
            "tôi đang nói vào micro và chữ hiện ra ngay khi tôi nói",
            "nhiều người có thể dùng cùng lúc mà máy chủ vẫn phản hồi nhanh",
            "khi mô hình thật sẵn sàng chỉ cần thay engine này là xong");

    private static final int BYTES_PER_SAMPLE = 2;
    private static final int WORD_MILLIS = 250;

    private final Duration chunkLatency;
    private final Duration segment;

    public MockSpeechEngine(SttProperties properties) {
        this.chunkLatency = properties.mock().chunkLatency();
        this.segment = properties.mock().segment();
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public SpeechSession openSession(int sampleRate) {
        return new MockSession(sampleRate);
    }

    private final class MockSession implements SpeechSession {

        private final long bytesPerWord;
        private final long bytesPerSegment;
        private long segmentBytes;
        private int sentence;
        private String lastPartial = "";

        MockSession(int sampleRate) {
            long bytesPerSecond = (long) sampleRate * BYTES_PER_SAMPLE;
            this.bytesPerWord = bytesPerSecond * WORD_MILLIS / 1000;
            this.bytesPerSegment = bytesPerSecond * segment.toMillis() / 1000;
        }

        @Override
        public Optional<TranscriptEvent> accept(byte[] pcm, int length) {
            simulateProcessing();
            segmentBytes += length;
            if (segmentBytes >= bytesPerSegment) {
                return Optional.of(commitSentence());
            }
            String partial = words((int) (segmentBytes / bytesPerWord));
            if (partial.equals(lastPartial)) {
                return Optional.empty();
            }
            lastPartial = partial;
            return Optional.of(TranscriptEvent.partial(partial));
        }

        @Override
        public TranscriptEvent finish() {
            if (segmentBytes == 0) {
                return TranscriptEvent.finalResult("");
            }
            return commitSentence();
        }

        @Override
        public void close() {
            // Nothing to release.
        }

        /** Parks for the configured latency; parkNanos may return early, so wait out the deadline. */
        private void simulateProcessing() {
            long deadline = System.nanoTime() + chunkLatency.toNanos();
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0) {
                LockSupport.parkNanos(remaining);
            }
        }

        private TranscriptEvent commitSentence() {
            String text = SENTENCES.get(sentence % SENTENCES.size());
            sentence++;
            segmentBytes = 0;
            lastPartial = "";
            return TranscriptEvent.finalResult(text);
        }

        private String words(int count) {
            String[] all = SENTENCES.get(sentence % SENTENCES.size()).split(" ");
            return String.join(" ", Arrays.copyOf(all, Math.min(count, all.length)));
        }
    }
}
