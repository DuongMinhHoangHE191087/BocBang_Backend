package com.bocbang.backend.stt;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param sampleRate the only sample rate the models accept (16 kHz); realtime audio at other rates is rejected
 * @param maxUtteranceSeconds audio buffered per realtime utterance (WebSocket, sync REST) before it is rejected
 * @param threads CPU threads passed to the native C++ engines (transcribe.cpp, whisper.cpp, llama.cpp)
 * @param nativeLibDir directory containing {@code libbocbang_*.so}; searches {@code native/dist/*} and
 *     {@code java.library.path} if blank
 * @param unloadBeforeRefine frees the ASR model before loading the Qwen 4B LLM so both never occupy RAM at once
 * @param defaultModel key of {@link #models} used when the client does not choose; first model if blank
 */
@ConfigurationProperties("stt")
public record SttProperties(
        @DefaultValue("16000") int sampleRate,
        @DefaultValue("60") int maxUtteranceSeconds,
        @DefaultValue("4") int threads,
        String nativeLibDir,
        @DefaultValue("true") boolean unloadBeforeRefine,
        String defaultModel,
        Map<String, Model> models,
        @DefaultValue Vad vad,
        @DefaultValue Refiner refiner,
        @DefaultValue Ffmpeg ffmpeg,
        @DefaultValue Jobs jobs) {

    public int maxUtteranceBytes() {
        return maxUtteranceSeconds * sampleRate * 2;
    }

    /** Native C++ JNI backend that loads and runs a model file. */
    public enum Backend {
        /** {@code libbocbang_transcribe.so} (transcribe.cpp) for {@code qwen3-asr-1.7b-q4_k_m.gguf}. */
        TRANSCRIBE_CPP,
        /** {@code libbocbang_whisper.so} (whisper.cpp) for {@code whisper-small-en.bin}. */
        WHISPER_CPP
    }

    /**
     * One ASR model loaded through JNI.
     *
     * @param backend native engine that understands {@link #modelPath}
     * @param modelPath path to the {@code .gguf} or {@code .bin} model file on disk
     * @param language ISO language code ({@code "en"}, {@code "vi"}) or blank for auto-detection
     * @param prompt optional initial prompt / context
     */
    public record Model(
            @DefaultValue("TRANSCRIBE_CPP") Backend backend,
            String modelPath,
            String language,
            String prompt,
            @DefaultValue("5m") Duration timeout) {
    }

    /**
     * Silero VAD ({@code ggml-silero-vad.bin}) loaded through {@code libbocbang_whisper.so}.
     * If {@code modelPath} is blank or missing on disk, long audio is split into fixed
     * {@code maxSegmentSeconds} slices without VAD.
     */
    public record Vad(
            String modelPath,
            @DefaultValue("0.5") float threshold,
            @DefaultValue("250") int minSpeechDurationMs,
            @DefaultValue("100") int minSilenceDurationMs,
            @DefaultValue("30") int speechPadMs,
            @DefaultValue("30") double maxSegmentSeconds,
            @DefaultValue("1.0") double maxMergeGapSeconds) {
    }

    /**
     * Qwen3-4B-Instruct ({@code qwen3-4b-instruct-2507-q4_k_m.gguf}) loaded through
     * {@code libbocbang_llama.so} to fix punctuation, casing, and obvious ASR errors.
     */
    public record Refiner(
            @DefaultValue("true") boolean enabled,
            String modelPath,
            String prompt,
            @DefaultValue("2048") int maxTokens,
            @DefaultValue("0.1") float temperature,
            @DefaultValue("1500") int maxBatchChars,
            @DefaultValue("10m") Duration timeout) {
    }

    /** Converts uploaded audio of any common format to 16-bit mono WAV at {@link #sampleRate}. */
    public record Ffmpeg(
            @DefaultValue({"ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", "{input}",
                    "-vn", "-map_metadata", "-1", "-ac", "1", "-ar", "{sample_rate}", "-c:a", "pcm_s16le",
                    "-f", "wav", "{output}"}) List<String> command,
            @DefaultValue("15m") Duration timeout) {
    }

    /** Background transcription of long uploads. */
    public record Jobs(
            String directory,
            @DefaultValue("20") int maxQueued,
            @DefaultValue("24h") Duration resultTtl) {
    }
}
