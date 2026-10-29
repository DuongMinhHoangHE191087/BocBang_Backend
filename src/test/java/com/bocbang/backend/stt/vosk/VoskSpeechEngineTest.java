package com.bocbang.backend.stt.vosk;

import static org.assertj.core.api.Assertions.assertThat;

import com.bocbang.backend.TestSettings;
import com.bocbang.backend.stt.SpeechSession;
import com.bocbang.backend.stt.TranscriptEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/** Loads the real native Vosk library and model. Skipped until the model is unpacked to {@link #MODEL_PATH}. */
class VoskSpeechEngineTest {

    static final String MODEL_PATH = "models/vosk-model-small-vn-0.4";

    @Test
    @EnabledIf("modelIsInstalled")
    void recognizesSilenceWithoutErrors() throws Exception {
        try (VoskSpeechEngine engine = new VoskSpeechEngine(TestSettings.properties("vosk.model-path", MODEL_PATH));
             SpeechSession session = engine.openSession(16000)) {
            byte[] oneSecondOfSilence = new byte[16000 * 2];

            session.accept(oneSecondOfSilence, oneSecondOfSilence.length);
            TranscriptEvent result = session.finish();

            assertThat(result.type()).isEqualTo(TranscriptEvent.Type.FINAL);
            assertThat(result.text()).isBlank();
        }
    }

    static boolean modelIsInstalled() {
        return Files.isDirectory(Path.of(MODEL_PATH));
    }
}
