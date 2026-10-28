package com.bocbang.backend.stt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("stt")
public record SttProperties(@DefaultValue("16000") int defaultSampleRate, Vosk vosk) {

    /** Directory of an unpacked Vosk model, relative to the working directory unless absolute. */
    public record Vosk(String modelPath) {
    }
}
