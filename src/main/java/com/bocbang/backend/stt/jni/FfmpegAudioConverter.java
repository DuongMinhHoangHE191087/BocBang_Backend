package com.bocbang.backend.stt.jni;

import com.bocbang.backend.stt.AudioConverter;
import com.bocbang.backend.stt.SpeechRecognitionException;
import com.bocbang.backend.stt.SttProperties;
import com.bocbang.backend.stt.Wav;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Normalizes uploaded audio (mp3, m4a, ogg, wav of any rate/channels) to 16-bit mono PCM WAV at
 * {@code stt.sample-rate}. Canonical 16 kHz mono WAV uploads skip {@code ffmpeg} altogether.
 */
@Component
public class FfmpegAudioConverter implements AudioConverter {

    private final int sampleRate;
    private final SttProperties.Ffmpeg config;

    public FfmpegAudioConverter(SttProperties properties) {
        this.sampleRate = properties.sampleRate();
        this.config = properties.ffmpeg();
        if (config.command() == null || config.command().isEmpty()) {
            throw new IllegalStateException("stt.ffmpeg.command is not configured");
        }
    }

    @Override
    public void convert(Path input, Path output) {
        if (isAlreadyNormalized(input)) {
            try {
                Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException ignored) {
                // Fall back to ffmpeg below.
            }
        }
        List<String> command = config.command().stream()
                .map(arg -> arg
                        .replace("{input}", input.toAbsolutePath().toString())
                        .replace("{output}", output.toAbsolutePath().toString())
                        .replace("{sample_rate}", Integer.toString(sampleRate)))
                .toList();
        Path stderrFile = null;
        try {
            stderrFile = Files.createTempFile("bocbang-ffmpeg-", ".err");
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(stderrFile.toFile())
                    .start();
            process.getOutputStream().close();
            boolean finished = process.waitFor(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new SpeechRecognitionException("ffmpeg timed out after " + config.timeout().toMillis() + " ms");
            }
            if (process.exitValue() != 0) {
                String err = Files.readString(stderrFile, StandardCharsets.UTF_8).strip();
                throw new IllegalArgumentException("Cannot decode audio file (ffmpeg exit "
                        + process.exitValue() + "): " + err);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpeechRecognitionException("Interrupted while converting audio", e);
        } catch (IOException e) {
            throw new SpeechRecognitionException("Failed to run ffmpeg: " + e.getMessage(), e);
        } finally {
            if (stderrFile != null) {
                try {
                    Files.deleteIfExists(stderrFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private boolean isAlreadyNormalized(Path input) {
        try {
            Wav.Info info = Wav.read(input);
            return info.isPcm16Mono(sampleRate) && info.dataOffset() == Wav.HEADER_BYTES && info.dataLength() > 0;
        } catch (IOException e) {
            return false;
        }
    }
}
