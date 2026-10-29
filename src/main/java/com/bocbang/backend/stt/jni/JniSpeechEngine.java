package com.bocbang.backend.stt.jni;

import com.bocbang.backend.stt.SpeechRecognitionException;
import com.bocbang.backend.stt.SpeechSession;
import com.bocbang.backend.stt.SpeechToTextEngine;
import com.bocbang.backend.stt.SttProperties;
import com.bocbang.backend.stt.SttProperties.Backend;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-memory JNI ASR engine supporting {@code qwen3-asr-1.7b-q4_k_m.gguf} ({@code transcribe.cpp})
 * and {@code whisper-small-en.bin} ({@code whisper.cpp}). Keeps at most one heavy ASR model loaded
 * in native RAM at a time, reusing it across all speech segments of a job.
 */
@Component
public class JniSpeechEngine implements SpeechToTextEngine, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JniSpeechEngine.class);

    private final SttProperties properties;
    private final CpuExecutionGate gate;
    private final Map<String, SttProperties.Model> models;
    private final String defaultModel;

    private String loadedModelName;
    private SttProperties.Model loadedConfig;
    private long nativeHandle;

    public JniSpeechEngine(SttProperties properties, CpuExecutionGate gate) {
        this.properties = properties;
        this.gate = gate;
        if (properties.models() == null || properties.models().isEmpty()) {
            throw new IllegalStateException("stt.models is not configured");
        }
        Map<String, SttProperties.Model> copy = new LinkedHashMap<>();
        properties.models().forEach((name, cfg) -> {
            if (cfg.modelPath() == null || cfg.modelPath().isBlank()) {
                throw new IllegalStateException("stt.models." + name + ".model-path is not configured");
            }
            copy.put(name, cfg);
        });
        this.models = Map.copyOf(copy);
        this.defaultModel = selectDefault(properties.defaultModel(), this.models);
    }

    @Override
    public String name() {
        return "jni:" + defaultModel;
    }

    @Override
    public String defaultModel() {
        return defaultModel;
    }

    @Override
    public List<String> models() {
        return List.copyOf(models.keySet());
    }

    @Override
    public SpeechSession openSession(int sampleRate) {
        if (sampleRate != properties.sampleRate()) {
            throw new IllegalArgumentException("Only " + properties.sampleRate()
                    + " Hz audio is supported, got " + sampleRate + " Hz");
        }
        return new JniSpeechSession(this, properties.maxUtteranceBytes(), properties.maxUtteranceSeconds());
    }

    @Override
    public String transcribe(String model, float[] samples) {
        String key = model == null || model.isBlank() ? defaultModel : model;
        SttProperties.Model cfg = models.get(key);
        if (cfg == null) {
            throw new IllegalArgumentException("Unknown ASR model '" + key + "', available: " + models.keySet());
        }
        if (samples == null || samples.length == 0) {
            return "";
        }
        return gate.call(cfg.timeout(), () -> abortActive(cfg.backend()), () -> {
            long handle = ensureLoaded(key, cfg);
            String raw = switch (cfg.backend()) {
                case TRANSCRIBE_CPP -> TranscribeNative.transcribe(
                        handle, samples, nullToEmpty(cfg.language()), nullToEmpty(cfg.prompt()));
                case WHISPER_CPP -> WhisperNative.whisperTranscribe(
                        handle, samples, properties.threads(), nullToEmpty(cfg.language()));
            };
            return raw == null ? "" : raw.trim();
        });
    }

    @Override
    public void releaseModels() {
        gate.call(null, null, () -> {
            unloadCurrent();
            return null;
        });
    }

    @Override
    public void close() {
        releaseModels();
    }

    private long ensureLoaded(String name, SttProperties.Model cfg) {
        if (nativeHandle != 0 && name.equals(loadedModelName)) {
            return nativeHandle;
        }
        unloadCurrent();
        Path file = Path.of(cfg.modelPath());
        if (!Files.isRegularFile(file)) {
            throw new SpeechRecognitionException("ASR model file not found: " + file.toAbsolutePath());
        }
        log.info("Loading ASR model '{}' ({}) from {}", name, cfg.backend(), file.toAbsolutePath());
        long handle = switch (cfg.backend()) {
            case TRANSCRIBE_CPP -> {
                NativeLoader.load("bocbang_transcribe", properties.nativeLibDir());
                yield TranscribeNative.init(file.toAbsolutePath().toString(), properties.threads());
            }
            case WHISPER_CPP -> {
                NativeLoader.load("bocbang_whisper", properties.nativeLibDir());
                yield WhisperNative.whisperInit(file.toAbsolutePath().toString());
            }
        };
        this.loadedModelName = name;
        this.loadedConfig = cfg;
        this.nativeHandle = handle;
        return handle;
    }

    private void abortActive(Backend backend) {
        long handle = this.nativeHandle;
        if (handle == 0) {
            return;
        }
        if (backend == Backend.TRANSCRIBE_CPP) {
            TranscribeNative.abort(handle);
        } else {
            WhisperNative.whisperAbort(handle);
        }
    }

    private void unloadCurrent() {
        if (nativeHandle == 0) {
            return;
        }
        log.info("Unloading ASR model '{}'", loadedModelName);
        long handle = nativeHandle;
        Backend backend = loadedConfig.backend();
        nativeHandle = 0;
        loadedModelName = null;
        loadedConfig = null;
        if (backend == Backend.TRANSCRIBE_CPP) {
            TranscribeNative.free(handle);
        } else {
            WhisperNative.whisperFree(handle);
        }
    }

    private static String selectDefault(String configured, Map<String, SttProperties.Model> models) {
        if (configured == null || configured.isBlank()) {
            return models.keySet().iterator().next();
        }
        if (!models.containsKey(configured)) {
            throw new IllegalStateException("stt.default-model '" + configured
                    + "' is not in stt.models " + models.keySet());
        }
        return configured;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
