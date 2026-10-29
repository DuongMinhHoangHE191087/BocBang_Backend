package com.bocbang.backend.stt.jni;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bocbang.backend.stt.SpeechRecognitionException;
import com.bocbang.backend.stt.SpeechSegment;
import com.bocbang.backend.stt.SpeechSession;
import com.bocbang.backend.stt.SttProperties;
import com.bocbang.backend.stt.SttProperties.Backend;
import com.bocbang.backend.stt.Wav;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

class JniSpeechEngineTest {

    private static final String HOST_LIB_DIR = "native/dist/host";
    private static final String LOCAL_MODEL_DIR = "/workspace/MeetingNotes-models";

    @TempDir
    Path tempDir;

    @Test
    void mergesNearbySegmentsAndCapsLength() {
        List<SpeechSegment> input = List.of(
                new SpeechSegment(0.0, 0.4),
                new SpeechSegment(0.5, 2.0),
                new SpeechSegment(5.0, 35.0));

        assertThat(JniVoiceActivityDetector.mergeAndCap(input, 10.0, 0.5)).containsExactly(
                new SpeechSegment(0.0, 2.0),
                new SpeechSegment(5.0, 15.0),
                new SpeechSegment(15.0, 25.0),
                new SpeechSegment(25.0, 35.0));
    }

    @Test
    void stripsQwenThinkBlocksAndEndTokens() {
        assertThat(JniTranscriptRefiner.clean("<think>\nstep 1\n</think>\nXin chào các bạn.<|im_end|>"))
                .isEqualTo("Xin chào các bạn.");
    }

    @Test
    void rejectsSampleRateOtherThan16kHzAndOversizedUtterances() {
        try (CpuExecutionGate gate = new CpuExecutionGate();
             JniSpeechEngine engine = new JniSpeechEngine(properties(1), gate)) {
            assertThatThrownBy(() -> engine.openSession(8000))
                    .isInstanceOf(IllegalArgumentException.class);

            try (SpeechSession session = engine.openSession(16000)) {
                byte[] oneSecond = new byte[16000 * 2];
                assertThat(session.accept(oneSecond, oneSecond.length)).isEmpty();
                assertThatThrownBy(() -> session.accept(new byte[2], 2))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    @EnabledIf("hostJniLibrariesBuilt")
    void loadsAllThreeJniSharedLibrariesWithoutGgmlSymbolCollisions() throws Exception {
        // Loading all three .so files in the same JVM verifies that exports.map hides internal ggml_* symbols.
        NativeLoader.load("bocbang_transcribe", HOST_LIB_DIR);
        NativeLoader.load("bocbang_whisper", HOST_LIB_DIR);
        NativeLoader.load("bocbang_llama", HOST_LIB_DIR);

        Path bogusFile = Files.writeString(tempDir.resolve("bogus.gguf"), "not a model");
        SttProperties props = new SttProperties(
                16000, 60, 2, HOST_LIB_DIR, true, "qwen3-asr",
                Map.of("qwen3-asr", new SttProperties.Model(
                        Backend.TRANSCRIBE_CPP, bogusFile.toString(), "", "", Duration.ofSeconds(10))),
                null, null, null, null);

        try (CpuExecutionGate gate = new CpuExecutionGate();
             JniSpeechEngine engine = new JniSpeechEngine(props, gate)) {
            assertThatThrownBy(() -> engine.transcribe("qwen3-asr", new float[1600]))
                    .isInstanceOf(SpeechRecognitionException.class)
                    .hasMessageContaining("transcribe.cpp");
        }
    }

    @Test
    @EnabledIf("sileroVadAndWhisperInstalled")
    void runsRealSileroVadAndWhisperThroughJni() throws Exception {
        SttProperties.Vad vadCfg = new SttProperties.Vad(
                LOCAL_MODEL_DIR + "/ggml-silero-vad.bin", 0.5f, 250, 100, 30, 30.0, 1.0);
        SttProperties props = new SttProperties(
                16000, 60, 4, HOST_LIB_DIR, true, "whisper-en",
                Map.of("whisper-en", new SttProperties.Model(
                        Backend.WHISPER_CPP, LOCAL_MODEL_DIR + "/whisper-small-en.bin", "en", "",
                        Duration.ofMinutes(2))),
                vadCfg, null, null, null);

        Path silenceWav = tempDir.resolve("silence.wav");
        byte[] twoSecondsSilence = new byte[2 * 16000 * 2];
        try (OutputStream out = Files.newOutputStream(silenceWav)) {
            out.write(Wav.header(twoSecondsSilence.length, 16000));
            out.write(twoSecondsSilence);
        }

        try (CpuExecutionGate gate = new CpuExecutionGate();
             JniVoiceActivityDetector vad = new JniVoiceActivityDetector(props, gate);
             JniSpeechEngine engine = new JniSpeechEngine(props, gate)) {
            assertThat(vad.detect(silenceWav)).isEmpty();
            String text = engine.transcribe("whisper-en", new float[16000]);
            assertThat(text).isNotNull();
        }
    }

    static boolean hostJniLibrariesBuilt() {
        return Files.isRegularFile(Path.of(HOST_LIB_DIR, System.mapLibraryName("bocbang_transcribe")))
                && Files.isRegularFile(Path.of(HOST_LIB_DIR, System.mapLibraryName("bocbang_whisper")))
                && Files.isRegularFile(Path.of(HOST_LIB_DIR, System.mapLibraryName("bocbang_llama")));
    }

    static boolean sileroVadAndWhisperInstalled() {
        return hostJniLibrariesBuilt()
                && Files.isRegularFile(Path.of(LOCAL_MODEL_DIR, "ggml-silero-vad.bin"))
                && Files.isRegularFile(Path.of(LOCAL_MODEL_DIR, "whisper-small-en.bin"));
    }

    private static SttProperties properties(int maxUtteranceSeconds) {
        return new SttProperties(
                16000, maxUtteranceSeconds, 2, HOST_LIB_DIR, true, "qwen3-asr",
                Map.of("qwen3-asr", new SttProperties.Model(
                        Backend.TRANSCRIBE_CPP, "/nonexistent/model.gguf", "", "", Duration.ofSeconds(10))),
                null, null, null, null);
    }
}
