package com.bocbang.backend.stt;

import java.time.Instant;
import java.util.List;

/** State of an asynchronous transcription job, suitable for returning directly as JSON. */
public record TranscriptionJob(
        String jobId,
        String model,
        boolean refine,
        Status status,
        Stage stage,
        double progress,
        Double durationSeconds,
        int completedSegments,
        int totalSegments,
        String rawText,
        String text,
        List<Segment> segments,
        String error,
        Instant createdAt,
        Instant updatedAt) {

    public enum Status {
        QUEUED,
        RUNNING,
        DONE,
        FAILED,
        CANCELLED;

        public boolean isTerminal() {
            return this == DONE || this == FAILED || this == CANCELLED;
        }
    }

    public enum Stage {
        QUEUED,
        CONVERTING,
        DETECTING_SPEECH,
        TRANSCRIBING,
        REFINING,
        DONE,
        FAILED,
        CANCELLED
    }

    public record Segment(int index, double start, double end, String text) {
    }
}
