package com.bocbang.backend.stt;

import com.bocbang.backend.stt.TranscriptionJob.Segment;
import com.bocbang.backend.stt.TranscriptionJob.Stage;
import com.bocbang.backend.stt.TranscriptionJob.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

/**
 * Runs long-audio transcription in the background: convert with ffmpeg, split with Silero VAD
 * (JNI), transcribe each speech segment in-memory with the chosen JNI ASR model, then optionally
 * clean up the joined text with the Qwen 4B JNI refiner.
 */
@Service
public class TranscriptionJobService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionJobService.class);
    private static final Pattern JOB_DIRECTORY =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final SttProperties properties;
    private final SpeechToTextEngine engine;
    private final VoiceActivityDetector vad;
    private final TranscriptRefiner refiner;
    private final AudioConverter converter;
    private final MeterRegistry meters;
    private final Counter audioSecondsProcessed;
    private final Path jobsRoot;
    private final ThreadPoolExecutor executor;
    private final boolean ownsRoot;
    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();

    public TranscriptionJobService(
            SttProperties properties,
            SpeechToTextEngine engine,
            VoiceActivityDetector vad,
            TranscriptRefiner refiner,
            AudioConverter converter) throws IOException {
        this(properties, engine, vad, refiner, converter, new SimpleMeterRegistry());
    }

    @Autowired
    public TranscriptionJobService(
            SttProperties properties,
            SpeechToTextEngine engine,
            VoiceActivityDetector vad,
            TranscriptRefiner refiner,
            AudioConverter converter,
            MeterRegistry meters) throws IOException {
        this.properties = properties;
        this.engine = engine;
        this.vad = vad;
        this.refiner = refiner;
        this.converter = converter;
        this.meters = meters;
        String configuredDir = properties.jobs().directory();
        this.ownsRoot = configuredDir == null || configuredDir.isBlank();
        this.jobsRoot = ownsRoot
                ? Files.createTempDirectory("bocbang-jobs-")
                : Files.createDirectories(Path.of(configuredDir));
        if (!ownsRoot) {
            sweepStaleJobDirectories(jobsRoot);
        }
        this.executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(Math.max(1, properties.jobs().maxQueued())),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        Gauge.builder("stt.jobs.active", executor, ThreadPoolExecutor::getActiveCount)
                .description("Long-audio transcription jobs currently running")
                .register(meters);
        Gauge.builder("stt.jobs.queued", executor, p -> p.getQueue().size())
                .description("Long-audio transcription jobs waiting in queue")
                .register(meters);
        Gauge.builder("stt.jobs.stored", jobs, Map::size)
                .description("Long-audio jobs currently held in memory")
                .register(meters);
        this.audioSecondsProcessed = Counter.builder("stt.jobs.audio.seconds")
                .baseUnit("seconds")
                .description("Total audio duration processed by long-audio jobs")
                .register(meters);
    }

    public TranscriptionJob submit(InputStream audio, String originalFilename, String model, Boolean refine)
            throws IOException {
        evictExpired();
        String chosenModel = resolveModel(model);
        boolean shouldRefine = (refine == null ? properties.refiner().enabled() : refine)
                && properties.refiner().enabled();
        if (executor.getQueue().remainingCapacity() == 0) {
            recordSubmitted(chosenModel, shouldRefine, "rejected_queue_full");
            // Known before the upload is copied: a client cannot make the server write 500 MB to refuse it.
            throw new SpeechRecognitionException("Transcription queue is full, try again later");
        }

        String id = UUID.randomUUID().toString();
        Path jobDir = Files.createDirectories(jobsRoot.resolve(id));
        Path uploaded = jobDir.resolve("upload" + safeExtension(originalFilename));
        long bytes = Files.copy(audio, uploaded, StandardCopyOption.REPLACE_EXISTING);
        if (bytes == 0) {
            deleteQuietly(jobDir);
            recordSubmitted(chosenModel, shouldRefine, "invalid");
            throw new IllegalArgumentException("Audio file is empty");
        }

        JobState state = new JobState(id, chosenModel, shouldRefine, jobDir);
        jobs.put(id, state);
        try {
            state.future = executor.submit(() -> runJob(state, uploaded));
        } catch (RejectedExecutionException e) {
            jobs.remove(id);
            deleteQuietly(jobDir);
            recordSubmitted(chosenModel, shouldRefine, "rejected_queue_full");
            throw new SpeechRecognitionException("Transcription queue is full, try again later", e);
        }
        recordSubmitted(chosenModel, shouldRefine, "accepted");
        return state.snapshot();
    }

    public Optional<TranscriptionJob> find(String jobId) {
        evictExpired();
        JobState state = jobs.get(jobId);
        return state == null ? Optional.empty() : Optional.of(state.snapshot());
    }

    /** Cancels a running job (triggering native C++ abort) and removes the job from the store. */
    public boolean delete(String jobId) {
        JobState state = jobs.remove(jobId);
        if (state == null) {
            return false;
        }
        state.cancel();
        Future<?> future = state.future;
        if (future != null) {
            future.cancel(true);
        }
        deleteQuietly(state.jobDir);
        recordJobDuration(state, Status.CANCELLED);
        return true;
    }

    private void runJob(JobState state, Path uploaded) {
        try {
            state.stage(Stage.CONVERTING, 0.02);
            Path normalized = state.jobDir.resolve("normalized.wav");
            long convertStarted = System.nanoTime();
            try {
                converter.convert(uploaded, normalized);
            } catch (RuntimeException e) {
                log.warn("Converting the upload of job {} failed", state.jobId, e);
                throw new JobFailure("The audio file could not be converted, "
                        + "check that it is a valid audio or video file");
            } finally {
                recordStage(Stage.CONVERTING, state.model, System.nanoTime() - convertStarted);
            }
            Files.deleteIfExists(uploaded);
            state.checkCancelled();

            Wav.Info wavInfo = Wav.read(normalized);
            if (!wavInfo.isPcm16Mono(properties.sampleRate())) {
                throw new JobFailure("The converted audio is not 16-bit mono at "
                        + properties.sampleRate() + " Hz; check stt.ffmpeg.command");
            }
            state.durationSeconds = roundMillis(wavInfo.durationSeconds());
            audioSecondsProcessed.increment(Math.max(0.0, wavInfo.durationSeconds()));

            state.stage(Stage.DETECTING_SPEECH, 0.08);
            long vadStarted = System.nanoTime();
            List<SpeechSegment> speech;
            try {
                speech = vad.detect(normalized);
            } finally {
                recordStage(Stage.DETECTING_SPEECH, state.model, System.nanoTime() - vadStarted);
            }
            state.totalSegments = speech.size();
            state.checkCancelled();

            state.stage(Stage.TRANSCRIBING, 0.10);
            long transcribeStarted = System.nanoTime();
            Counter segmentCounter = Counter.builder("stt.jobs.segments")
                    .description("Speech segments transcribed across long-audio jobs")
                    .tag("model", state.model)
                    .register(meters);
            try {
                for (int i = 0; i < speech.size(); i++) {
                    state.checkCancelled();
                    SpeechSegment span = speech.get(i);
                    float[] samples = Wav.readFloats(normalized, wavInfo, span.start(), span.end());
                    String piece = engine.transcribe(state.model, samples).trim();
                    segmentCounter.increment();
                    if (!piece.isEmpty()) {
                        state.segments.add(new Segment(
                                state.segments.size(), roundMillis(span.start()), roundMillis(span.end()), piece));
                    }
                    double ratio = (i + 1.0) / speech.size();
                    state.segmentCompleted(i + 1, 0.10 + 0.80 * ratio);
                }
            } finally {
                recordStage(Stage.TRANSCRIBING, state.model, System.nanoTime() - transcribeStarted);
            }

            String raw = state.segments.stream()
                    .map(Segment::text)
                    .collect(Collectors.joining(" "));
            state.rawText = raw;

            String finalText = raw;
            if (state.refine && !raw.isBlank()) {
                if (properties.unloadBeforeRefine()) {
                    engine.releaseModels();
                }
                state.stage(Stage.REFINING, 0.92);
                long refineStarted = System.nanoTime();
                try {
                    finalText = refineInBatches(state, raw);
                } finally {
                    recordStage(Stage.REFINING, state.model, System.nanoTime() - refineStarted);
                    if (properties.unloadBeforeRefine()) {
                        refiner.releaseModel();
                    }
                }
            }
            state.complete(finalText);
            recordJobDuration(state, Status.DONE);
        } catch (CancelledException e) {
            state.markCancelled();
            recordJobDuration(state, Status.CANCELLED);
        } catch (Throwable e) {
            if (state.status == Status.CANCELLED || Thread.currentThread().isInterrupted()) {
                state.markCancelled();
                recordJobDuration(state, Status.CANCELLED);
                return;
            }
            log.warn("Transcription job {} failed", state.jobId, e);
            // The log has the cause; the client gets only messages written for it, never paths or tool output.
            state.fail(e instanceof JobFailure ? e.getMessage() : "Transcription failed, see the server log");
            recordJobDuration(state, Status.FAILED);
        } finally {
            deleteQuietly(state.jobDir);
        }
    }

    private void recordSubmitted(String model, boolean refine, String outcome) {
        Counter.builder("stt.jobs.submitted")
                .description("Long-audio job submissions by model and outcome")
                .tag("model", model)
                .tag("refine", Boolean.toString(refine))
                .tag("outcome", outcome)
                .register(meters)
                .increment();
    }

    private void recordStage(Stage stage, String model, long nanos) {
        Timer.builder("stt.jobs.stage.duration")
                .description("Time spent in one stage of a long-audio transcription job")
                .tag("stage", stage.name())
                .tag("model", model)
                .register(meters)
                .record(Math.max(0L, nanos), TimeUnit.NANOSECONDS);
    }

    private void recordJobDuration(JobState state, Status terminalStatus) {
        if (!state.durationRecorded.compareAndSet(false, true)) {
            return;
        }
        Timer.builder("stt.jobs.duration")
                .description("End-to-end wall-clock time of a long-audio job from submission to completion")
                .tag("model", state.model)
                .tag("refine", Boolean.toString(state.refine))
                .tag("status", terminalStatus.name())
                .register(meters)
                .record(Math.max(0L, System.nanoTime() - state.submittedNanos), TimeUnit.NANOSECONDS);
    }

    private String refineInBatches(JobState state, String raw) {
        List<String> batches = splitBatches(raw, Math.max(200, properties.refiner().maxBatchChars()));
        List<String> refined = new ArrayList<>(batches.size());
        for (int i = 0; i < batches.size(); i++) {
            state.checkCancelled();
            refined.add(refiner.refine(batches.get(i)));
            state.stage(Stage.REFINING, 0.92 + 0.07 * ((i + 1.0) / batches.size()));
        }
        return String.join(" ", refined).trim();
    }

    static List<String> splitBatches(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return List.of(text);
        }
        List<String> batches = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maxChars);
            if (end < text.length()) {
                int space = text.lastIndexOf(' ', end);
                if (space > start) {
                    end = space;
                }
            }
            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) {
                batches.add(piece);
            }
            // Only a space is the separator; a cut inside a word keeps the character it lands on.
            start = end < text.length() && text.charAt(end) == ' ' ? end + 1 : end;
        }
        return batches;
    }

    private String resolveModel(String requested) {
        String name = requested == null || requested.isBlank() ? engine.defaultModel() : requested;
        if (!engine.models().contains(name)) {
            throw new IllegalArgumentException("Unknown ASR model '" + name + "', available: " + engine.models());
        }
        return name;
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(properties.jobs().resultTtl());
        jobs.values().removeIf(state -> state.status.isTerminal() && state.updatedAt.isBefore(cutoff));
    }

    private static String safeExtension(String filename) {
        if (filename == null) {
            return ".bin";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return ".bin";
        }
        String ext = filename.substring(dot).replaceAll("[^.a-zA-Z0-9]", "");
        return ext.length() <= 10 ? ext : ".bin";
    }

    private static double roundMillis(double seconds) {
        return Math.round(seconds * 1000.0) / 1000.0;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            FileSystemUtils.deleteRecursively(dir);
        } catch (IOException e) {
            log.debug("Could not delete job directory {}", dir, e);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        // Queued jobs never run, so nothing else removes their uploads; a running one cleans up in its own finally.
        jobs.values().forEach(state -> {
            state.cancel();
            deleteQuietly(state.jobDir);
        });
        jobs.clear();
        if (ownsRoot) {
            deleteQuietly(jobsRoot);
        }
    }

    /**
     * Jobs live in memory, so after a restart every job directory left in a configured root is orphaned. Only
     * directories named like a job id are removed, in case the root is shared with something else.
     */
    private static void sweepStaleJobDirectories(Path root) {
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .filter(dir -> JOB_DIRECTORY.matcher(dir.getFileName().toString()).matches())
                    .forEach(TranscriptionJobService::deleteQuietly);
        } catch (IOException e) {
            log.warn("Could not look for old job directories in {}", root, e);
        }
    }

    private static final class CancelledException extends RuntimeException {
    }

    /** A failure whose message was written for the client, unlike the exceptions the tools and models throw. */
    private static final class JobFailure extends RuntimeException {
        JobFailure(String message) {
            super(message);
        }
    }

    private static final class JobState {
        final String jobId;
        final String model;
        final boolean refine;
        final Path jobDir;
        final Instant createdAt = Instant.now();
        final long submittedNanos = System.nanoTime();
        final java.util.concurrent.atomic.AtomicBoolean durationRecorded =
                new java.util.concurrent.atomic.AtomicBoolean();
        final List<Segment> segments = new CopyOnWriteArrayList<>();

        volatile Future<?> future;
        volatile Status status = Status.QUEUED;
        volatile Stage stage = Stage.QUEUED;
        volatile double progress;
        volatile Double durationSeconds;
        volatile int completedSegments;
        volatile int totalSegments;
        volatile String rawText;
        volatile String text;
        volatile String error;
        volatile Instant updatedAt = createdAt;

        JobState(String jobId, String model, boolean refine, Path jobDir) {
            this.jobId = jobId;
            this.model = model;
            this.refine = refine;
            this.jobDir = jobDir;
        }

        synchronized void stage(Stage nextStage, double nextProgress) {
            if (status == Status.CANCELLED) {
                throw new CancelledException();
            }
            status = Status.RUNNING;
            stage = nextStage;
            progress = Math.round(nextProgress * 1000.0) / 1000.0;
            updatedAt = Instant.now();
        }

        synchronized void segmentCompleted(int completed, double nextProgress) {
            completedSegments = completed;
            progress = Math.round(nextProgress * 1000.0) / 1000.0;
            updatedAt = Instant.now();
        }

        synchronized void complete(String finalText) {
            text = finalText;
            status = Status.DONE;
            stage = Stage.DONE;
            progress = 1.0;
            updatedAt = Instant.now();
        }

        synchronized void fail(String message) {
            error = message;
            status = Status.FAILED;
            stage = Stage.FAILED;
            updatedAt = Instant.now();
        }

        synchronized void cancel() {
            status = Status.CANCELLED;
            stage = Stage.CANCELLED;
            updatedAt = Instant.now();
        }

        synchronized void markCancelled() {
            status = Status.CANCELLED;
            stage = Stage.CANCELLED;
            updatedAt = Instant.now();
        }

        void checkCancelled() {
            if (status == Status.CANCELLED || Thread.currentThread().isInterrupted()) {
                throw new CancelledException();
            }
        }

        TranscriptionJob snapshot() {
            return new TranscriptionJob(
                    jobId, model, refine, status, stage, progress, durationSeconds,
                    completedSegments, totalSegments, rawText, text, List.copyOf(segments),
                    error, createdAt, updatedAt);
        }
    }
}
