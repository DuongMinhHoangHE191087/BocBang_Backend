package com.bocbang.backend.stt;

/** A stretch of speech in an audio file, in seconds from the start. */
public record SpeechSegment(double start, double end) {

    public double duration() {
        return end - start;
    }
}
