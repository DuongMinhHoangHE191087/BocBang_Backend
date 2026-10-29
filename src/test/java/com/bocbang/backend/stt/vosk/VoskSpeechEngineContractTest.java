package com.bocbang.backend.stt.vosk;

import com.bocbang.backend.TestSettings;
import com.bocbang.backend.stt.SpeechToTextEngine;
import com.bocbang.backend.stt.SpeechToTextEngineContract;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;

/** Holds the real Vosk engine to the shared contract. Skipped until the model is unpacked. */
@EnabledIf("modelIsInstalled")
class VoskSpeechEngineContractTest extends SpeechToTextEngineContract {

    private static VoskSpeechEngine engine;

    @BeforeAll
    static void loadModelOnce() throws IOException {
        engine = new VoskSpeechEngine(TestSettings.properties("vosk.model-path", VoskSpeechEngineTest.MODEL_PATH));
    }

    @AfterAll
    static void unloadModel() {
        if (engine != null) {
            engine.close();
        }
    }

    @Override
    protected SpeechToTextEngine engine() {
        return engine;
    }

    static boolean modelIsInstalled() {
        return Files.isDirectory(Path.of(VoskSpeechEngineTest.MODEL_PATH));
    }
}
