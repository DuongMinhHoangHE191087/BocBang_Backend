package com.bocbang.backend.stt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bocbang.backend.runtime.CapacityExceededException;
import com.bocbang.backend.runtime.InferenceExecutor;
import com.bocbang.backend.runtime.SessionLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TranscriptionServiceTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final InferenceExecutor executor = new InferenceExecutor(2, meters);
    private final SessionLimiter limiter = new SessionLimiter(1, meters);

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void streamsWavPcmThroughEngineAndReturnsFinalText() throws Exception {
        FakeSpeechToTextEngine engine = new FakeSpeechToTextEngine("xin chào");
        TranscriptionService service = new TranscriptionService(engine, limiter, executor);

        byte[] oneSecondOfSilence = new byte[16000 * 2];
        TranscriptionService.Transcription result =
                service.transcribeWav(new ByteArrayInputStream(wav(16000, 16, oneSecondOfSilence)));

        assertThat(result.text()).isEqualTo("xin chào");
        assertThat(result.sampleRate()).isEqualTo(16000);
        assertThat(result.durationMillis()).isEqualTo(1000);
        assertThat(engine.openedSampleRates).containsExactly(16000);
        assertThat(engine.receivedBytes.get()).isEqualTo(oneSecondOfSilence.length);
        assertThat(engine.closedSessions.get()).isEqualTo(1);
        assertThat(limiter.active()).as("the slot is released after the file is done").isZero();
    }

    @Test
    void refusesWhenEveryRecognitionSlotIsTaken() throws Exception {
        TranscriptionService service = new TranscriptionService(new FakeSpeechToTextEngine("unused"), limiter, executor);
        limiter.tryAcquire().orElseThrow();
        byte[] audio = wav(16000, 16, new byte[3200]);

        assertThatThrownBy(() -> service.transcribeWav(new ByteArrayInputStream(audio)))
                .isInstanceOf(CapacityExceededException.class);
    }

    @Test
    void rejectsWavThatIsNotSixteenBitMono() throws Exception {
        TranscriptionService service = new TranscriptionService(new FakeSpeechToTextEngine("unused"), limiter, executor);
        byte[] eightBit = wav(8000, 8, new byte[8000]);

        assertThatThrownBy(() -> service.transcribeWav(new ByteArrayInputStream(eightBit)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(limiter.active()).isZero();
    }

    @Test
    @Timeout(10)
    void rejectsWavWhoseSampleRateIsOutsideTheSupportedRangeBeforeTakingASlot() throws Exception {
        FakeSpeechToTextEngine engine = new FakeSpeechToTextEngine("unused");
        TranscriptionService service = new TranscriptionService(engine, limiter, executor);

        for (float sampleRate : new float[] {1, 4000, 96000}) {
            byte[] audio = wav(sampleRate, 16, new byte[3200]);

            assertThatThrownBy(() -> service.transcribeWav(new ByteArrayInputStream(audio)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sample rate");
        }
        assertThat(engine.openedSampleRates).isEmpty();
        assertThat(limiter.active()).isZero();
    }

    @Test
    @Timeout(15)
    void doesNotCloseTheSessionUnderAnInferenceThreadWhenTheRequestIsInterrupted() throws Exception {
        GatedEngine engine = new GatedEngine();
        TranscriptionService service = new TranscriptionService(engine, limiter, executor);
        byte[] audio = wav(16000, 16, new byte[3200]);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread request = new Thread(() -> {
            try {
                service.transcribeWav(new ByteArrayInputStream(audio));
            } catch (Throwable e) {
                outcome.set(e);
            }
        });
        request.start();
        assertThat(engine.enteredAccept.await(5, TimeUnit.SECONDS)).isTrue();

        request.interrupt();
        Thread.sleep(200);
        engine.gate.countDown();
        request.join(5000);

        assertThat(request.isAlive()).isFalse();
        assertThat(outcome.get()).isInstanceOf(IllegalStateException.class);
        assertThat(engine.closedWhileBusy.get()).isFalse();
        assertThat(engine.closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(limiter.active()).isZero();
    }

    @Test
    void rejectsInputThatIsNotWav() {
        TranscriptionService service = new TranscriptionService(new FakeSpeechToTextEngine("unused"), limiter, executor);

        assertThatThrownBy(() -> service.transcribeWav(new ByteArrayInputStream("hello".getBytes())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Holds one engine call open so a test can act while an inference thread is inside the session. */
    private static final class GatedEngine implements SpeechToTextEngine {

        final CountDownLatch enteredAccept = new CountDownLatch(1);
        final CountDownLatch gate = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicBoolean busy = new AtomicBoolean();
        final AtomicBoolean closedWhileBusy = new AtomicBoolean();

        @Override
        public String name() {
            return "gated";
        }

        @Override
        public SpeechSession openSession(int sampleRate) {
            return new SpeechSession() {
                @Override
                public Optional<TranscriptEvent> accept(byte[] pcm, int length) {
                    busy.set(true);
                    enteredAccept.countDown();
                    try {
                        gate.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    busy.set(false);
                    return Optional.empty();
                }

                @Override
                public TranscriptEvent finish() {
                    return TranscriptEvent.finalResult("");
                }

                @Override
                public void close() {
                    if (busy.get()) {
                        closedWhileBusy.set(true);
                    }
                    closed.countDown();
                }
            };
        }
    }

    private static byte[] wav(float sampleRate, int bits, byte[] data) throws IOException {
        // 8-bit WAV is unsigned by definition; 16-bit WAV is signed little-endian.
        AudioFormat format = new AudioFormat(sampleRate, bits, 1, bits == 16, false);
        AudioInputStream stream =
                new AudioInputStream(new ByteArrayInputStream(data), format, data.length / format.getFrameSize());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AudioSystem.write(stream, AudioFileFormat.Type.WAVE, out);
        return out.toByteArray();
    }
}
