package com.bocbang.backend.stt;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("stt")
public record SttProperties(
        /** Which {@link SpeechToTextEngine} to load: {@code vosk} (default) or {@code mock}. */
        @DefaultValue("vosk") String engine,
        @DefaultValue("16000") int defaultSampleRate,
        @DefaultValue Vosk vosk,
        @DefaultValue Mock mock,
        @DefaultValue Pool pool,
        @DefaultValue Session session,
        @DefaultValue WebSocket websocket) {

    /** Directory of an unpacked Vosk model, relative to the working directory unless absolute. */
    public record Vosk(@DefaultValue("models/vosk-model-small-vn-0.4") String modelPath) {
    }

    /** Scripted engine for demos and load tests when no model is installed. */
    public record Mock(
            /** Simulated processing time per engine call. */
            @DefaultValue("0ms") Duration chunkLatency,
            /** Audio length after which the mock commits a sentence as final. */
            @DefaultValue("3s") Duration segment) {
    }

    /** Shared inference threads and how many streams they are allowed to serve. */
    public record Pool(
            /** Inference threads. 0 means CPU count minus one, leaving a core for the web server. */
            @DefaultValue("0") int workers,
            /** Concurrent streams (WebSocket sessions and file uploads). 0 means workers times the engine's own figure. */
            @DefaultValue("0") int maxSessions) {

        public int resolvedWorkers() {
            return workers > 0 ? workers : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        }

        public int resolvedMaxSessions(int engineSessionsPerWorker) {
            return maxSessions > 0 ? maxSessions : resolvedWorkers() * engineSessionsPerWorker;
        }
    }

    public record Session(
            /** Audio a client may have queued before the server gives up on it as too slow to serve. */
            @DefaultValue("5s") Duration maxBufferedAudio,
            /** Most audio merged into one engine call, which also bounds how long one session holds a worker. */
            @DefaultValue("500ms") Duration maxBatchAudio) {
    }

    public record WebSocket(
            @DefaultValue("*") List<String> allowedOrigins,
            /** Largest binary frame accepted. Bigger frames close the connection with code 1009. */
            @DefaultValue("65536") int maxFrameBytes,
            /** Connections that send nothing for this long are closed. */
            @DefaultValue("60s") Duration idleTimeout,
            /** A client that cannot receive for this long is dropped. */
            @DefaultValue("10s") Duration sendTimeout,
            /** Transcript bytes that may wait for a slow client before it is dropped. */
            @DefaultValue("524288") int sendBufferBytes) {
    }
}
