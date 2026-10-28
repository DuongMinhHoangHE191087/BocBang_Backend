package com.bocbang.backend.api;

import com.bocbang.backend.stt.SpeechToTextEngine;
import com.bocbang.backend.stt.SttProperties;
import com.bocbang.backend.stt.TranscriptionService;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
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

    public SttController(SpeechToTextEngine engine, SttProperties properties, TranscriptionService transcriptionService) {
        this.engine = engine;
        this.properties = properties;
        this.transcriptionService = transcriptionService;
    }

    @GetMapping("/status")
    public SttStatusResponse status() {
        return new SttStatusResponse(engine.name(), properties.defaultSampleRate(), "/ws/stt");
    }

    /** Transcribes an uploaded 16-bit mono WAV file in one request. */
    @PostMapping(path = "/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TranscriptionResponse transcribe(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Audio file is empty");
        }
        TranscriptionService.Transcription result = transcriptionService.transcribeWav(file.getInputStream());
        return new TranscriptionResponse(result.text(), result.sampleRate());
    }

    public record SttStatusResponse(String engine, int defaultSampleRate, String websocketPath) {
    }

    public record TranscriptionResponse(String text, int sampleRate) {
    }
}
