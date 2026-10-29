package com.bocbang.backend.stt;

/** The model failed to produce a transcript (process crashed, timed out, could not start). */
public class SpeechRecognitionException extends RuntimeException {

    public SpeechRecognitionException(String message) {
        super(message);
    }

    public SpeechRecognitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
