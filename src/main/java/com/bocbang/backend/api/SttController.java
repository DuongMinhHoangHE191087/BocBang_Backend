package com.bocbang.backend.api;

import com.bocbang.backend.runtime.InferenceExecutor;
import com.bocbang.backend.runtime.SessionLimiter;
import com.bocbang.backend.stt.SpeechToTextEngine;
import com.bocbang.backend.stt.SttProperties;
import com.bocbang.backend.stt.TranscriptionService;
import java.io.IOException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(path = "/api/v1/stt", produces = MediaType.APPLICATION_JSON_VALUE)
public class SttController {

    private final SpeechToTextEngine engine;
    private final SttProperties properties;
    private final SessionLimiter limiter;
    private final InferenceExecutor executor;
    private final TranscriptionService transcriptionService;

    public SttController(SpeechToTextEngine engine, SttProperties properties, SessionLimiter limiter,
            InferenceExecutor executor, TranscriptionService transcriptionService) {
        this.engine = engine;
        this.properties = properties;
        this.limiter = limiter;
        this.executor = executor;
        this.transcriptionService = transcriptionService;
    }

    /** Engine, WebSocket location and live load, so a client can check there is room before it connects. */
    @GetMapping("/status")
    public ResponseEntity<SttStatusResponse> status() {
        Capacity capacity = new Capacity(executor.workers(), limiter.max(), limiter.active(), executor.queued());
        SttStatusResponse body = new SttStatusResponse(
                engine.name(), properties.defaultSampleRate(), "/ws/stt", capacity, !limiter.isFull());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** Transcribes an uploaded 16-bit mono WAV file in one request. */
    @PostMapping(path = "/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TranscriptionResponse transcribe(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Audio file is empty");
        }
        TranscriptionService.Transcription result = transcriptionService.transcribeWav(file.getInputStream());
        return new TranscriptionResponse(result.text(), result.sampleRate(), result.durationMillis());
    }

    public record SttStatusResponse(
            String engine, int defaultSampleRate, String websocketPath, Capacity capacity, boolean ready) {
    }

    public record Capacity(int workers, int maxSessions, int activeSessions, int queuedTasks) {
    }

    public record TranscriptionResponse(String text, int sampleRate, long durationMs) {
    }
}
