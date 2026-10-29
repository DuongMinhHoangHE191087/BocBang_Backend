package com.bocbang.backend.stt;

/** The sample rates every entry point accepts, so a stream and an uploaded file are held to the same limits. */
public final class SampleRates {

    public static final int MIN = 8000;
    public static final int MAX = 48000;

    private SampleRates() {
    }

    public static boolean isSupported(int sampleRate) {
        return sampleRate >= MIN && sampleRate <= MAX;
    }
}
