package com.bocbang.backend.stt;

public record TranscriptEvent(Type type, String text) {

    public enum Type {
        PARTIAL,
        FINAL
    }

    public static TranscriptEvent partial(String text) {
        return new TranscriptEvent(Type.PARTIAL, text);
    }

    public static TranscriptEvent finalResult(String text) {
        return new TranscriptEvent(Type.FINAL, text);
    }
}
