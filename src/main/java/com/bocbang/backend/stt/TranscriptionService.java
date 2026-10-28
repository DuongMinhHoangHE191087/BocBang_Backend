package com.bocbang.backend.stt;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.springframework.stereotype.Service;

/** Runs a complete audio file through the same streaming engine used by the WebSocket. */
@Service
public class TranscriptionService {

    /** 250 ms of 16 kHz 16-bit mono audio per engine call. */
    private static final int CHUNK_BYTES = 8000;

    private final SpeechToTextEngine engine;

    public TranscriptionService(SpeechToTextEngine engine) {
        this.engine = engine;
    }

    public Transcription transcribeWav(InputStream wav) throws IOException {
        try (AudioInputStream audio = AudioSystem.getAudioInputStream(wav)) {
            AudioFormat format = audio.getFormat();
            if (format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
                    || format.getSampleSizeInBits() != 16
                    || format.getChannels() != 1
                    || format.isBigEndian()) {
                throw new IllegalArgumentException("Only 16-bit little-endian PCM mono WAV is supported");
            }
            int sampleRate = Math.round(format.getSampleRate());
            byte[] pcm = audio.readAllBytes();
            return new Transcription(transcribePcm(pcm, sampleRate), sampleRate);
        } catch (UnsupportedAudioFileException e) {
            throw new IllegalArgumentException("File is not a valid WAV audio", e);
        }
    }

    private String transcribePcm(byte[] pcm, int sampleRate) {
        List<String> utterances = new ArrayList<>();
        try (SpeechSession session = engine.openSession(sampleRate)) {
            for (int offset = 0; offset < pcm.length; offset += CHUNK_BYTES) {
                int length = Math.min(CHUNK_BYTES, pcm.length - offset);
                byte[] chunk = Arrays.copyOfRange(pcm, offset, offset + length);
                session.accept(chunk, length)
                        .filter(event -> event.type() == TranscriptEvent.Type.FINAL)
                        .ifPresent(event -> utterances.add(event.text()));
            }
            utterances.add(session.finish().text());
        }
        return utterances.stream()
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining(" "));
    }

    public record Transcription(String text, int sampleRate) {
    }
}
