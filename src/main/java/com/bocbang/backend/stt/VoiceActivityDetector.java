package com.bocbang.backend.stt;

import java.nio.file.Path;
import java.util.List;

/** Finds where people are speaking so long audio can be transcribed in model-sized pieces. */
public interface VoiceActivityDetector {

    /** @param wav 16-bit mono PCM WAV at the engine's sample rate */
    List<SpeechSegment> detect(Path wav);
}
