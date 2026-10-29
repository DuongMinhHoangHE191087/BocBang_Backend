package com.bocbang.backend.stt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bocbang.backend.stt.TranscriptionJob.Status;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranscriptionJobServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void convertsSplitsTranscribesUnloadsAsrAndRefinesLongAudioInBackground() throws Exception {
        FakeSpeechToTextEngine engine = new FakeSpeechToTextEngine("xin chao");
        VoiceActivityDetector vad = wav -> List.of(
                new SpeechSegment(0.0, 1.0),
                new SpeechSegment(1.5, 3.0));
        AtomicInteger refinerReleased = new AtomicInteger();
        TranscriptRefiner refiner = new TranscriptRefiner() {
            @Override
            public String refine(String transcript) {
                return transcript.toUpperCase();
            }

            @Override
            public void releaseModel() {
                refinerReleased.incrementAndGet();
            }
        };
        AudioConverter converter = (in, out) -> copy(in, out);

        try (TranscriptionJobService service = new TranscriptionJobService(
                properties(true), engine, vad, refiner, converter)) {
            TranscriptionJob submitted = service.submit(
                    new ByteArrayInputStream(wavSeconds(3)), "lecture.mp3", "whisper-en", true);
            TranscriptionJob done = awaitTerminal(service, submitted.jobId());

            assertThat(done.status()).isEqualTo(Status.DONE);
            assertThat(done.model()).isEqualTo("whisper-en");
            assertThat(done.progress()).isEqualTo(1.0);
            assertThat(done.durationSeconds()).isEqualTo(3.0);
            assertThat(done.totalSegments()).isEqualTo(2);
            assertThat(done.completedSegments()).isEqualTo(2);
            assertThat(done.segments()).hasSize(2);
            assertThat(done.segments().get(0).start()).isEqualTo(0.0);
            assertThat(done.segments().get(1).end()).isEqualTo(3.0);
            assertThat(done.rawText()).isEqualTo("xin chao xin chao");
            assertThat(done.text()).isEqualTo("XIN CHAO XIN CHAO");
            assertThat(engine.requestedModels).containsExactly("whisper-en", "whisper-en");
            assertThat(engine.releasedCount.get()).isEqualTo(1);
            assertThat(refinerReleased.get()).isEqualTo(1);
        }
    }

    @Test
    void exposesPartialSegmentsAndProgressWhileJobIsRunning() throws Exception {
        CountDownLatch firstSegmentDone = new CountDownLatch(1);
        CountDownLatch allowSecondSegment = new CountDownLatch(1);
        AtomicInteger call = new AtomicInteger();

        SpeechToTextEngine slowEngine = new FakeSpeechToTextEngine("unused") {
            @Override
            public String transcribe(String model, float[] samples) {
                if (call.incrementAndGet() == 1) {
                    return "câu một";
                }
                firstSegmentDone.countDown();
                try {
                    assertThat(allowSecondSegment.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "câu hai";
            }
        };
        VoiceActivityDetector vad = wav -> List.of(new SpeechSegment(0, 1), new SpeechSegment(1, 2));

        try (TranscriptionJobService service = new TranscriptionJobService(
                properties(false), slowEngine, vad, text -> text, (in, out) -> copy(in, out))) {
            TranscriptionJob submitted = service.submit(
                    new ByteArrayInputStream(wavSeconds(2)), "call.wav", null, false);

            assertThat(firstSegmentDone.await(5, TimeUnit.SECONDS)).isTrue();
            TranscriptionJob mid = service.find(submitted.jobId()).orElseThrow();
            assertThat(mid.status()).isEqualTo(Status.RUNNING);
            assertThat(mid.completedSegments()).isEqualTo(1);
            assertThat(mid.totalSegments()).isEqualTo(2);
            assertThat(mid.segments()).extracting(TranscriptionJob.Segment::text).containsExactly("câu một");
            assertThat(mid.progress()).isBetween(0.4, 0.6);

            allowSecondSegment.countDown();
            TranscriptionJob done = awaitTerminal(service, submitted.jobId());
            assertThat(done.text()).isEqualTo("câu một câu hai");
        }
    }

    @Test
    void deleteCancelsInFlightJobAndRemovesTemporaryFiles() throws Exception {
        CountDownLatch inModel = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        SpeechToTextEngine blockingEngine = new FakeSpeechToTextEngine("unused") {
            @Override
            public String transcribe(String model, float[] samples) {
                inModel.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return "";
            }
        };

        try (TranscriptionJobService service = new TranscriptionJobService(
                properties(false), blockingEngine, wav -> List.of(new SpeechSegment(0, 1)),
                text -> text, (in, out) -> copy(in, out))) {
            TranscriptionJob job = service.submit(
                    new ByteArrayInputStream(wavSeconds(1)), "long.wav", null, false);
            assertThat(inModel.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(service.delete(job.jobId())).isTrue();
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(service.find(job.jobId())).isEmpty();
        }
    }

    @Test
    void rejectsUnknownModelBeforeQueueing() throws Exception {
        try (TranscriptionJobService service = new TranscriptionJobService(
                properties(false), new FakeSpeechToTextEngine("x"), wav -> List.of(),
                text -> text, (in, out) -> copy(in, out))) {
            assertThatThrownBy(() -> service.submit(
                    new ByteArrayInputStream(wavSeconds(1)), "a.wav", "no-such-model", false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no-such-model");
        }
    }

    @Test
    void wavReadFloatsReadsExactSampleWindow() throws Exception {
        byte[] samples = new byte[4 * 16000 * 2];
        // Fill second [1, 2) with +16384 (0x4000 little-endian -> 0.5f).
        for (int i = 16000; i < 2 * 16000; i++) {
            samples[i * 2] = 0x00;
            samples[i * 2 + 1] = 0x40;
        }
        Path source = tempDir.resolve("full.wav");
        try (OutputStream out = Files.newOutputStream(source)) {
            out.write(Wav.header(samples.length, 16000));
            out.write(samples);
        }
        float[] window = Wav.readFloats(source, Wav.read(source), 1.0, 2.0);
        assertThat(window).hasSize(16000);
        assertThat(window[0]).isEqualTo(0.5f);
        assertThat(window[window.length - 1]).isEqualTo(0.5f);
    }

    private TranscriptionJob awaitTerminal(TranscriptionJobService service, String jobId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            TranscriptionJob job = service.find(jobId).orElseThrow();
            if (job.status().isTerminal()) {
                return job;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Job " + jobId + " did not finish in time");
    }

    private SttProperties properties(boolean refineEnabled) {
        return new SttProperties(
                16000, 60, 4, null, true, "qwen3-asr", Map.of(),
                null,
                new SttProperties.Refiner(refineEnabled, null, null, 2048, 0.1f, 1500, Duration.ofSeconds(5)),
                null,
                new SttProperties.Jobs(tempDir.toString(), 5, Duration.ofHours(1)));
    }

    private static byte[] wavSeconds(int seconds) throws IOException {
        int pcmBytes = seconds * 16000 * 2;
        byte[] header = Wav.header(pcmBytes, 16000);
        byte[] wav = new byte[header.length + pcmBytes];
        System.arraycopy(header, 0, wav, 0, header.length);
        return wav;
    }

    private static void copy(Path in, Path out) {
        try {
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
