package com.bocbang.backend.stt;

import com.bocbang.backend.runtime.CapacityExceededException;
import com.bocbang.backend.runtime.InferenceExecutor;
import com.bocbang.backend.runtime.SessionLimiter;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.springframework.stereotype.Service;

/**
 * Runs a complete audio file through the same streaming engine used by the WebSocket. It takes a recognition slot
 * and runs on the shared inference threads, one chunk at a time, so a long upload shares the CPU fairly with
 * live streams instead of crowding them out.
 */
@Service
public class TranscriptionService {

    private static final int BYTES_PER_SAMPLE = 2;

    private final SpeechToTextEngine engine;
    private final SttProperties properties;
    private final SessionLimiter limiter;
    private final InferenceExecutor executor;

    public TranscriptionService(SpeechToTextEngine engine, SttProperties properties, SessionLimiter limiter,
            InferenceExecutor executor) {
        this.engine = engine;
        this.properties = properties;
        this.limiter = limiter;
        this.executor = executor;
    }

    /**
     * @throws IllegalArgumentException if the input is not a 16-bit little-endian mono PCM WAV at the model's rate
     * @throws CapacityExceededException if every recognition slot is taken
     */
    public Transcription transcribeWav(InputStream wav) throws IOException {
        byte[] pcm;
        int sampleRate;
        // AudioSystem sniffs the format with mark/reset, which uploaded-file streams do not support.
        try (AudioInputStream audio = AudioSystem.getAudioInputStream(new BufferedInputStream(wav))) {
            AudioFormat format = audio.getFormat();
            if (format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
                    || format.getSampleSizeInBits() != 16
                    || format.getChannels() != 1
                    || format.isBigEndian()) {
                throw new IllegalArgumentException("Only 16-bit little-endian PCM mono WAV is supported");
            }
            sampleRate = Math.round(format.getSampleRate());
            if (sampleRate != properties.sampleRate()) {
                throw new IllegalArgumentException(
                        "WAV sample rate must be " + properties.sampleRate() + " Hz, got " + sampleRate);
            }
            // Uploads may be hundreds of MB for the job endpoint; this one holds the audio in memory, so it reads
            // one frame past the limit to tell "too long" from "exactly at the limit" without reading the rest.
            // It must be a whole frame: AudioInputStream answers 0 to a partial-frame read, and readNBytes then
            // spins on that 0 forever.
            int limit = properties.maxUtteranceBytes();
            pcm = audio.readNBytes(limit + Math.max(1, format.getFrameSize()));
            if (pcm.length > limit) {
                throw new IllegalArgumentException("Audio is longer than the " + properties.maxUtteranceSeconds()
                        + " s limit for synchronous transcription, send it to /api/v1/stt/jobs instead");
            }
        } catch (UnsupportedAudioFileException e) {
            throw new IllegalArgumentException("File is not a valid WAV audio", e);
        }
        try (SessionLimiter.Permit ignored = limiter.tryAcquire()
                .orElseThrow(() -> new CapacityExceededException("All recognition slots are busy"))) {
            String text = transcribePcm(pcm, sampleRate);
            long durationMillis = pcm.length * 1000L / ((long) sampleRate * BYTES_PER_SAMPLE);
            return new Transcription(text, sampleRate, durationMillis);
        }
    }

    private String transcribePcm(byte[] pcm, int sampleRate) {
        // 250 ms of audio per engine call, kept sample-aligned.
        int chunkBytes = sampleRate * BYTES_PER_SAMPLE / 4 & ~1;
        List<String> utterances = new ArrayList<>();
        try (GuardedSession session = new GuardedSession(engine.openSession(sampleRate))) {
            for (int offset = 0; offset < pcm.length; offset += chunkBytes) {
                int length = Math.min(chunkBytes, pcm.length - offset);
                byte[] chunk = Arrays.copyOfRange(pcm, offset, offset + length);
                onInferenceThread(() -> session.accept(chunk, length))
                        .filter(event -> event.type() == TranscriptEvent.Type.FINAL)
                        .ifPresent(event -> utterances.add(event.text()));
            }
            utterances.add(onInferenceThread(session::finish).text());
        }
        return utterances.stream()
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining(" "));
    }

    private <T> T onInferenceThread(Callable<T> task) {
        try {
            return executor.call(task);
        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while transcribing", e);
        } catch (Exception e) {
            throw new IllegalStateException("Transcription failed", e);
        }
    }

    public record Transcription(String text, int sampleRate, long durationMillis) {
    }

    /**
     * A request thread that is interrupted stops waiting for its queued call, but that call may already be inside
     * the engine. Closing waits for it, and a call that starts after the close does nothing, so the session is
     * never freed under a running decode.
     */
    private static final class GuardedSession implements AutoCloseable {

        private final SpeechSession session;
        private final ReentrantLock lock = new ReentrantLock();
        private boolean closed;

        GuardedSession(SpeechSession session) {
            this.session = session;
        }

        Optional<TranscriptEvent> accept(byte[] pcm, int length) {
            lock.lock();
            try {
                return closed ? Optional.empty() : session.accept(pcm, length);
            } finally {
                lock.unlock();
            }
        }

        TranscriptEvent finish() {
            lock.lock();
            try {
                if (closed) {
                    throw new IllegalStateException("Session is closed");
                }
                return session.finish();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void close() {
            lock.lock();
            try {
                if (!closed) {
                    closed = true;
                    session.close();
                }
            } finally {
                lock.unlock();
            }
        }
    }
}
