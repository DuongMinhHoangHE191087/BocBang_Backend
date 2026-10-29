package com.bocbang.backend.api;

import com.bocbang.backend.stt.SpeechToTextEngine;
import com.bocbang.backend.stt.SttProperties;
import com.bocbang.backend.stt.TranscriptionJob;
import com.bocbang.backend.stt.TranscriptionJobService;
import com.bocbang.backend.stt.TranscriptionService;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/stt")
public class SttController {

    private final SpeechToTextEngine engine;
    private final SttProperties properties;
    private final TranscriptionService transcriptionService;
    private final TranscriptionJobService jobService;

    public SttController(
            SpeechToTextEngine engine,
            SttProperties properties,
            TranscriptionService transcriptionService,
            TranscriptionJobService jobService) {
        this.engine = engine;
        this.properties = properties;
        this.transcriptionService = transcriptionService;
        this.jobService = jobService;
    }

    @GetMapping("/status")
    public SttStatusResponse status() {
        return new SttStatusResponse(
                engine.name(),
                engine.defaultModel(),
                engine.models(),
                properties.refiner().enabled(),
                properties.sampleRate(),
                properties.maxUtteranceSeconds(),
                "/ws/stt",
                "/api/stt/jobs");
    }

    /** Synchronous transcription for short 16-bit mono WAV clips at the model's sample rate. */
    @PostMapping(path = "/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TranscriptionResponse transcribe(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Audio file is empty");
        }
        try (InputStream in = file.getInputStream()) {
            TranscriptionService.Transcription result = transcriptionService.transcribeWav(in);
            return new TranscriptionResponse(result.text(), result.sampleRate());
        }
    }

    /**
     * Queues a long audio file (WAV, MP3, M4A, OGG, FLAC...) for background transcription and returns
     * immediately. Poll {@code GET /api/stt/jobs/{jobId}} for progress and the finished transcript.
     */
    @PostMapping(path = "/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TranscriptionJob> submitJob(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "model", required = false) String model,
            @RequestParam(name = "refine", required = false) Boolean refine) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Audio file is empty");
        }
        try (InputStream in = file.getInputStream()) {
            TranscriptionJob job = jobService.submit(in, file.getOriginalFilename(), model, refine);
            return ResponseEntity.accepted()
                    .location(URI.create("/api/stt/jobs/" + job.jobId()))
                    .body(job);
        }
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<TranscriptionJob> getJob(@PathVariable String jobId) {
        return ResponseEntity.of(jobService.find(jobId));
    }

    @DeleteMapping("/jobs/{jobId}")
    public ResponseEntity<Void> deleteJob(@PathVariable String jobId) {
        return jobService.delete(jobId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    public record SttStatusResponse(
            String engine,
            String defaultModel,
            List<String> models,
            boolean refinerEnabled,
            int sampleRate,
            int maxUtteranceSeconds,
            String websocketPath,
            String jobsPath) {
    }

    public record TranscriptionResponse(String text, int sampleRate) {
    }
}
