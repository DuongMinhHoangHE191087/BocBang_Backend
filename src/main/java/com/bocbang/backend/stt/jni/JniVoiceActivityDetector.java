package com.bocbang.backend.stt.jni;

import com.bocbang.backend.stt.SpeechRecognitionException;
import com.bocbang.backend.stt.SpeechSegment;
import com.bocbang.backend.stt.SttProperties;
import com.bocbang.backend.stt.VoiceActivityDetector;
import com.bocbang.backend.stt.Wav;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs {@code ggml-silero-vad.bin} in-memory via {@code libbocbang_whisper.so}. Long files are
 * scanned in 5-minute windows (~19 MB float array per window) so a 1-hour recording never spikes
 * the Java heap on embedded boards.
 */
@Component
public class JniVoiceActivityDetector implements VoiceActivityDetector, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JniVoiceActivityDetector.class);
    private static final double WINDOW_SECONDS = 300.0;

    private final SttProperties properties;
    private final SttProperties.Vad config;
    private final CpuExecutionGate gate;

    private long vadHandle;

    public JniVoiceActivityDetector(SttProperties properties, CpuExecutionGate gate) {
        this.properties = properties;
        this.config = properties.vad();
        this.gate = gate;
    }

    @Override
    public List<SpeechSegment> detect(Path wav) {
        Wav.Info info;
        try {
            info = Wav.read(wav);
        } catch (IOException e) {
            throw new SpeechRecognitionException("Cannot read WAV header of " + wav.getFileName(), e);
        }
        double duration = info.durationSeconds();
        if (duration <= 0) {
            return List.of();
        }
        if (config.modelPath() == null || config.modelPath().isBlank()) {
            return sliceRange(0, duration, config.maxSegmentSeconds());
        }
        Path modelFile = Path.of(config.modelPath());
        if (!Files.isRegularFile(modelFile)) {
            log.warn("Silero VAD model not found at {}, falling back to {} s fixed slices",
                    modelFile.toAbsolutePath(), config.maxSegmentSeconds());
            return sliceRange(0, duration, config.maxSegmentSeconds());
        }

        List<SpeechSegment> rawSegments = new ArrayList<>();
        for (double windowStart = 0; windowStart + 1e-6 < duration; windowStart += WINDOW_SECONDS) {
            double windowEnd = Math.min(duration, windowStart + WINDOW_SECONDS);
            float[] samples = readWindow(wav, info, windowStart, windowEnd);
            double offset = windowStart;
            double[] bounds = gate.call(Duration.ofMinutes(5), null, () -> {
                long handle = ensureLoaded(modelFile);
                return WhisperNative.vadDetect(
                        handle,
                        samples,
                        config.threshold(),
                        config.minSpeechDurationMs(),
                        config.minSilenceDurationMs(),
                        (float) config.maxSegmentSeconds(),
                        config.speechPadMs());
            });
            for (int i = 0; i + 1 < bounds.length; i += 2) {
                double start = Math.max(offset, offset + bounds[i]);
                double end = Math.min(windowEnd, offset + bounds[i + 1]);
                if (end > start) {
                    rawSegments.add(new SpeechSegment(start, end));
                }
            }
        }
        return mergeAndCap(rawSegments, config.maxSegmentSeconds(), config.maxMergeGapSeconds());
    }

    @Override
    public void close() {
        gate.call(null, null, () -> {
            if (vadHandle != 0) {
                WhisperNative.vadFree(vadHandle);
                vadHandle = 0;
            }
            return null;
        });
    }

    private long ensureLoaded(Path modelFile) {
        if (vadHandle != 0) {
            return vadHandle;
        }
        NativeLoader.load("bocbang_whisper", properties.nativeLibDir());
        log.info("Loading Silero VAD model from {}", modelFile.toAbsolutePath());
        vadHandle = WhisperNative.vadInit(modelFile.toAbsolutePath().toString(), properties.threads());
        return vadHandle;
    }

    private static float[] readWindow(Path wav, Wav.Info info, double start, double end) {
        try {
            return Wav.readFloats(wav, info, start, end);
        } catch (IOException e) {
            throw new SpeechRecognitionException("Failed to read audio window from " + wav.getFileName(), e);
        }
    }

    public static List<SpeechSegment> mergeAndCap(
            List<SpeechSegment> detected, double maxSeconds, double maxGapSeconds) {
        List<SpeechSegment> split = new ArrayList<>();
        for (SpeechSegment segment : detected) {
            split.addAll(sliceRange(segment.start(), segment.end(), maxSeconds));
        }
        List<SpeechSegment> merged = new ArrayList<>();
        for (SpeechSegment next : split) {
            if (merged.isEmpty()) {
                merged.add(next);
                continue;
            }
            SpeechSegment current = merged.get(merged.size() - 1);
            if (next.start() - current.end() <= maxGapSeconds && next.end() - current.start() <= maxSeconds) {
                merged.set(merged.size() - 1, new SpeechSegment(current.start(), next.end()));
            } else {
                merged.add(next);
            }
        }
        return merged;
    }

    public static List<SpeechSegment> sliceRange(double start, double end, double maxSeconds) {
        List<SpeechSegment> slices = new ArrayList<>();
        for (double cursor = start; cursor + 1e-6 < end; cursor += maxSeconds) {
            slices.add(new SpeechSegment(cursor, Math.min(end, cursor + maxSeconds)));
        }
        return slices;
    }
}
