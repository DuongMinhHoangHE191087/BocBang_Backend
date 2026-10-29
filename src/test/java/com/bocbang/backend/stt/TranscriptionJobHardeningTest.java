package com.bocbang.backend.stt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.bocbang.backend.TestSettings;
import com.bocbang.backend.stt.TranscriptionJob.Status;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Failure and cleanup behaviour of the job service that the happy-path tests do not reach. */
class TranscriptionJobHardeningTest {

    @TempDir
    Path jobsDir;

    private final FakeSpeechToTextEngine engine = new FakeSpeechToTextEngine("ok");
    private final VoiceActivityDetector oneSecondOfSpeech = wav -> List.of(new SpeechSegment(0.0, 1.0));
    private final TranscriptRefiner untouched = text -> text;

    @Test
    void keepsEveryCharacterWhenALongTextWithoutSpacesIsSplit() {
        String text = "0123456789".repeat(45);

        List<String> batches = TranscriptionJobService.splitBatches(text, 200);

        assertThat(batches).hasSizeGreaterThan(1);
        assertThat(String.join("", batches)).isEqualTo(text);
    }

    @Test
    void keepsEveryWordWhenALongTextWithSpacesIsSplit() {
        String text = "xin chao cac ban ".repeat(60).trim();

        List<String> batches = TranscriptionJobService.splitBatches(text, 200);

        assertThat(String.join(" ", batches)).isEqualTo(text);
    }

    @Test
    void refusesAFullQueueBeforeReadingTheUploadAtAll() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AudioConverter blocking = (in, out) -> {
            started.countDown();
            waitFor(release);
            copy(in, out);
        };
        try (TranscriptionJobService service = service(blocking, "jobs.max-queued", "1")) {
            service.submit(new ByteArrayInputStream(wavSeconds(1)), "one.wav", null, false);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            service.submit(new ByteArrayInputStream(wavSeconds(1)), "two.wav", null, false);

            AtomicBoolean read = new AtomicBoolean();
            InputStream third = new FilterInputStream(new ByteArrayInputStream(wavSeconds(1))) {
                @Override
                public int read() throws IOException {
                    read.set(true);
                    return super.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    read.set(true);
                    return super.read(b, off, len);
                }
            };

            assertThatThrownBy(() -> service.submit(third, "three.wav", null, false))
                    .isInstanceOf(SpeechRecognitionException.class)
                    .hasMessageContaining("queue is full");
            assertThat(read).as("a full queue is known before the upload is copied to disk").isFalse();
            release.countDown();
        }
    }

    @Test
    void removesTheDirectoriesOfJobsThatNeverFinishedWhenItShutsDown() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AudioConverter blocking = (in, out) -> {
            started.countDown();
            waitFor(new CountDownLatch(1));
        };
        TranscriptionJobService service = service(blocking, "jobs.max-queued", "3");
        service.submit(new ByteArrayInputStream(wavSeconds(1)), "running.wav", null, false);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        service.submit(new ByteArrayInputStream(wavSeconds(1)), "queued.wav", null, false);
        assertThat(jobDirectories()).hasSize(2);

        service.close();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(jobDirectories()).isEmpty());
    }

    @Test
    void sweepsJobDirectoriesLeftByAnEarlierRunButNothingElse() throws Exception {
        Path stale = Files.createDirectories(jobsDir.resolve(UUID.randomUUID().toString()));
        Files.writeString(stale.resolve("upload.wav"), "left behind");
        Path unrelated = Files.createDirectories(jobsDir.resolve("keep-me"));
        Files.writeString(unrelated.resolve("notes.txt"), "not ours");

        try (TranscriptionJobService ignored = service((in, out) -> copy(in, out))) {
            assertThat(stale).doesNotExist();
            assertThat(unrelated.resolve("notes.txt")).exists();
        }
    }

    @Test
    void failsAJobWhoseConvertedAudioIsNotSixteenBitMonoAtTheModelRate() throws Exception {
        AudioConverter wrongRate = (in, out) -> {
            try {
                byte[] header = Wav.header(16000 * 2 * 2, 8000);
                byte[] wav = new byte[header.length + 16000 * 2 * 2];
                System.arraycopy(header, 0, wav, 0, header.length);
                Files.write(out, wav);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        try (TranscriptionJobService service = service(wrongRate)) {
            TranscriptionJob submitted = service.submit(new ByteArrayInputStream(wavSeconds(1)), "x.wav", null, false);

            TranscriptionJob done = awaitTerminal(service, submitted.jobId());

            assertThat(done.status()).isEqualTo(Status.FAILED);
            assertThat(done.error()).contains("16-bit mono");
            assertThat(engine.requestedModels).as("the model never saw the wrong audio").isEmpty();
        }
    }

    @Test
    void doesNotHandBackServerPathsOrToolOutputWhenAJobFails() throws Exception {
        AudioConverter failing = (in, out) -> {
            throw new SpeechRecognitionException("ffmpeg exited 1: /srv/bocbang/jobs/abc/upload.mp3: Invalid data");
        };
        try (TranscriptionJobService service = service(failing)) {
            TranscriptionJob submitted = service.submit(new ByteArrayInputStream(wavSeconds(1)), "x.mp3", null, false);

            TranscriptionJob done = awaitTerminal(service, submitted.jobId());

            assertThat(done.status()).isEqualTo(Status.FAILED);
            assertThat(done.error()).isNotBlank().doesNotContain("/srv").doesNotContain("ffmpeg").doesNotContain("upload");
        }
    }

    private TranscriptionJobService service(AudioConverter converter, String... extra) throws IOException {
        String[] settings = new String[extra.length + 4];
        settings[0] = "jobs.directory";
        settings[1] = jobsDir.toString();
        settings[2] = "refiner.enabled";
        settings[3] = "false";
        System.arraycopy(extra, 0, settings, 4, extra.length);
        return new TranscriptionJobService(
                TestSettings.properties(settings), engine, oneSecondOfSpeech, untouched, converter);
    }

    private List<Path> jobDirectories() throws IOException {
        try (Stream<Path> children = Files.list(jobsDir)) {
            return children.filter(Files::isDirectory).toList();
        }
    }

    private static TranscriptionJob awaitTerminal(TranscriptionJobService service, String jobId) {
        await().atMost(10, TimeUnit.SECONDS)
                .until(() -> service.find(jobId).orElseThrow().status().isTerminal());
        return service.find(jobId).orElseThrow();
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static byte[] wavSeconds(int seconds) {
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
