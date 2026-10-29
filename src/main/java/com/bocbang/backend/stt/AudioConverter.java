package com.bocbang.backend.stt;

import java.nio.file.Path;

/** Turns uploaded audio of any common format into what the models expect. */
public interface AudioConverter {

    /** Writes {@code input} to {@code output} as 16-bit mono PCM WAV at the engine's sample rate. */
    void convert(Path input, Path output);
}
