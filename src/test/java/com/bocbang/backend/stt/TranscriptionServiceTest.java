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
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
    void rejectsInputThatIsNotWav() {
        TranscriptionService service = new TranscriptionService(new FakeSpeechToTextEngine("unused"), limiter, executor);

        assertThatThrownBy(() -> service.transcribeWav(new ByteArrayInputStream("hello".getBytes())))
                .isInstanceOf(IllegalArgumentException.class);
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
