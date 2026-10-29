package com.bocbang.backend.stt.jni;

import com.bocbang.backend.stt.SpeechRecognitionException;
import com.bocbang.backend.stt.SttProperties;
import com.bocbang.backend.stt.TranscriptRefiner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs {@code qwen3-4b-instruct-2507-q4_k_m.gguf} in-memory via {@code libbocbang_llama.so} to fix
 * punctuation, casing, and obvious ASR recognition mistakes. If refinement fails, the caller keeps
 * the raw transcript rather than losing the transcription job.
 */
@Component
public class JniTranscriptRefiner implements TranscriptRefiner, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JniTranscriptRefiner.class);
    private static final String TEXT_PLACEHOLDER = "{text}";
    private static final Pattern THINK_BLOCKS = Pattern.compile("(?s)<think>.*?</think>");

    private static final String DEFAULT_PROMPT = """
            <|im_start|>system
            Correct punctuation, capitalization, and obvious speech recognition mistakes in the user's transcript. Keep the same language and meaning. Output only the corrected transcript.<|im_end|>
            <|im_start|>user
            {text}<|im_end|>
            <|im_start|>assistant
            """;

    private final SttProperties properties;
    private final SttProperties.Refiner config;
    private final CpuExecutionGate gate;
    private final String promptTemplate;

    private long llamaHandle;

    public JniTranscriptRefiner(SttProperties properties, CpuExecutionGate gate) {
        this.properties = properties;
        this.config = properties.refiner();
        this.gate = gate;
        this.promptTemplate = config.prompt() == null || config.prompt().isBlank()
                ? DEFAULT_PROMPT
                : config.prompt();
        if (!promptTemplate.contains(TEXT_PLACEHOLDER)) {
            throw new IllegalStateException("stt.refiner.prompt must contain " + TEXT_PLACEHOLDER);
        }
    }

    @Override
    public String refine(String transcript) {
        if (!config.enabled() || transcript == null || transcript.isBlank()) {
            return transcript;
        }
        if (config.modelPath() == null || config.modelPath().isBlank()) {
            return transcript;
        }
        Path modelFile = Path.of(config.modelPath());
        if (!Files.isRegularFile(modelFile)) {
            log.warn("Refiner model not found at {}, keeping raw transcript", modelFile.toAbsolutePath());
            return transcript;
        }
        String prompt = promptTemplate.replace(TEXT_PLACEHOLDER, transcript);
        try {
            String output = gate.call(config.timeout(), this::abortActive, () -> {
                long handle = ensureLoaded(modelFile);
                return LlamaNative.generate(
                        handle,
                        prompt,
                        config.maxTokens(),
                        properties.threads(),
                        config.temperature());
            });
            String cleaned = clean(output);
            return cleaned == null || cleaned.isBlank() ? transcript : cleaned;
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new SpeechRecognitionException("Interrupted during transcript refinement", e);
            }
            log.warn("Transcript refinement failed, keeping raw transcript: {}", e.getMessage());
            return transcript;
        }
    }

    @Override
    public void releaseModel() {
        gate.call(null, null, () -> {
            if (llamaHandle != 0) {
                log.info("Unloading Qwen refiner model");
                LlamaNative.free(llamaHandle);
                llamaHandle = 0;
            }
            return null;
        });
    }

    @Override
    public void close() {
        releaseModel();
    }

    private long ensureLoaded(Path modelFile) {
        if (llamaHandle != 0) {
            return llamaHandle;
        }
        NativeLoader.load("bocbang_llama", properties.nativeLibDir());
        log.info("Loading Qwen refiner model from {}", modelFile.toAbsolutePath());
        llamaHandle = LlamaNative.init(modelFile.toAbsolutePath().toString());
        return llamaHandle;
    }

    private void abortActive() {
        long handle = this.llamaHandle;
        if (handle != 0) {
            LlamaNative.abort(handle);
        }
    }

    static String clean(String output) {
        if (output == null) {
            return null;
        }
        return THINK_BLOCKS.matcher(output).replaceAll("")
                .replace("<|im_end|>", "")
                .replace("<|endoftext|>", "")
                .trim();
    }
}
