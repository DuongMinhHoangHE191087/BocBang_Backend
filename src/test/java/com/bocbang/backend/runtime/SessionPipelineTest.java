package com.bocbang.backend.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bocbang.backend.stt.SpeechRecognitionException;
import com.bocbang.backend.stt.TranscriptEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SessionPipelineTest {

    private static final int MAX_BUFFERED = 1 << 20;
    private static final int MAX_BATCH = 64;

    private final InferenceExecutor executor = new InferenceExecutor(1, new SimpleMeterRegistry());
    private final RecordingSession session = new RecordingSession();
    private final RecordingListener listener = new RecordingListener();

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void feedsChunksToTheSessionInArrivalOrder() {
        SessionPipeline pipeline = new SessionPipeline(session, executor, listener, MAX_BUFFERED, MAX_BATCH);

        int chunks = 200;
        for (int i = 0; i < chunks; i++) {
            assertThat(pipeline.offer(chunkWithIndex(i))).isTrue();
        }

        await().atMost(5, TimeUnit.SECONDS).until(() -> session.receivedBytes() == chunks * 2);
        ByteBuffer payload = ByteBuffer.wrap(session.receivedPayload()).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < chunks; i++) {
            assertThat((int) payload.getShort()).isEqualTo(i);
        }
    }

    @Test
    void neverUsesOneSessionFromTwoThreadsEvenWithManyProducersAndWorkers() throws Exception {
        InferenceExecutor wide = new InferenceExecutor(4, new SimpleMeterRegistry());
        ExecutorService producers = Executors.newFixedThreadPool(4);
        try {
            SessionPipeline pipeline = new SessionPipeline(session, wide, listener, MAX_BUFFERED, 2);
            session.onAccept = () -> Thread.onSpinWait();

            List<java.util.concurrent.Future<?>> done = new ArrayList<>();
            for (int p = 0; p < 4; p++) {
                done.add(producers.submit(() -> {
                    for (int i = 0; i < 250; i++) {
                        pipeline.offer(chunkWithIndex(i));
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : done) {
                f.get(5, TimeUnit.SECONDS);
            }

            await().atMost(5, TimeUnit.SECONDS).until(() -> session.receivedBytes() == 4 * 250 * 2);
            assertThat(session.overlapped).isFalse();
        } finally {
            producers.shutdownNow();
            wide.close();
        }
    }

    @Test
    void rejectsAudioBeyondTheBufferLimitWhileTheWorkerIsBusy() {
        CountDownLatch release = blockWorkers(1);
        SessionPipeline pipeline = new SessionPipeline(session, executor, listener, 250, MAX_BATCH);

        assertThat(pipeline.offer(new byte[100])).isTrue();
        assertThat(pipeline.offer(new byte[100])).isTrue();
        assertThat(pipeline.offer(new byte[100])).isFalse();

        release.countDown();
        await().atMost(5, TimeUnit.SECONDS).until(() -> session.receivedBytes() == 200);
        assertThat(pipeline.offer(new byte[100])).isTrue();
    }

    @Test
    void mergesQueuedChunksIntoOneEngineCall() {
        CountDownLatch release = blockWorkers(1);
        SessionPipeline pipeline = new SessionPipeline(session, executor, listener, MAX_BUFFERED, 1000);

        for (int i = 0; i < 5; i++) {
            pipeline.offer(new byte[100]);
        }
        release.countDown();

        await().atMost(5, TimeUnit.SECONDS).until(() -> session.receivedBytes() == 500);
        assertThat(session.chunkLengths).containsExactly(500);
    }

    @Test
    void closeWaitsForTheInFlightCallAndClosesTheSessionOnce() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        session.gate = gate;
        SessionPipeline pipeline = new SessionPipeline(session, executor, listener, MAX_BUFFERED, MAX_BATCH);
        pipeline.offer(new byte[10]);
        assertThat(session.enteredAccept.await(5, TimeUnit.SECONDS)).isTrue();

        pipeline.close();
        pipeline.close();
        assertThat(session.closeCount.get()).isZero();

        gate.countDown();
        await().atMost(5, TimeUnit.SECONDS).until(() -> session.closeCount.get() == 1);
        assertThat(session.closedWhileBusy).isFalse();
        assertThat(session.overlapped).isFalse();
    }

    @Test
    void closeDropsAudioThatHasNotStartedProcessing() {
        CountDownLatch release = blockWorkers(1);
        SessionPipeline pipeline = new SessionPipeline(session, executor, listener, MAX_BUFFERED, MAX_BATCH);
        pipeline.offer(new byte[10]);
        pipeline.offer(new byte[10]);

        pipeline.close();
        release.countDown();

        await().atMost(5, TimeUnit.SECONDS).until(() -> session.closeCount.get() == 1);
        assertThat(session.receivedBytes()).isZero();
        assertThat(pipeline.offer(new byte[10])).as("audio after close is ignored, not an overload").isTrue();
        assertThat(session.receivedBytes()).isZero();
    }

    @Test
    void reportsAFailureOnceAndStopsFeedingTheSession() throws Exception {
        session.failure = new IllegalStateException("model crashed");
        SessionPipeline pipeline = new SessionPipeline(session, executor, listener, MAX_BUFFERED, MAX_BATCH);

        pipeline.offer(new byte[10]);
        await().atMost(5, TimeUnit.SECONDS).until(() -> listener.failures.size() == 1);
        session.failure = null;
        pipeline.offer(new byte[10]);
        pipeline.offer(new byte[10]);
        pipeline.finish();
        Thread.sleep(100);

        assertThat(listener.failures).hasSize(1);
        assertThat(listener.failures.get(0)).hasMessage("model crashed");
        assertThat(session.receivedBytes()).isZero();
        assertThat(listener.events).isEmpty();

        pipeline.close();
        await().atMost(5, TimeUnit.SECONDS).until(() -> session.closeCount.get() == 1);
    }

    @Test
    void finishEmitsTheFinalTranscriptAfterAllQueuedAudio() {
        SessionPipeline pipeline = new SessionPipeline(session, executor, listener, MAX_BUFFERED, MAX_BATCH);

        pipeline.offer(new byte[10]);
        pipeline.finish();

        await().atMost(5, TimeUnit.SECONDS).until(() -> listener.events.size() == 2);
        assertThat(listener.events.get(0).type()).isEqualTo(TranscriptEvent.Type.PARTIAL);
        assertThat(listener.events.get(1)).isEqualTo(TranscriptEvent.finalResult("done"));
        assertThat(listener.lagsMillis).allSatisfy(lag -> assertThat(lag).isGreaterThanOrEqualTo(0));
    }

    @Test
    void answersABurstOfEndCommandsWithOneFinalTranscript() throws Exception {
        CountDownLatch release = blockWorkers(1);
        SessionPipeline pipeline = new SessionPipeline(session, executor, listener, MAX_BUFFERED, MAX_BATCH);

        for (int i = 0; i < 50; i++) {
            pipeline.finish();
        }
        release.countDown();

        await().atMost(5, TimeUnit.SECONDS).until(() -> !listener.events.isEmpty());
        Thread.sleep(100);
        assertThat(listener.events).containsExactly(TranscriptEvent.finalResult("done"));
    }

    @Test
    void keepsAnEndCommandThatFollowsNewAudio() {
        CountDownLatch release = blockWorkers(1);
        SessionPipeline pipeline = new SessionPipeline(session, executor, listener, MAX_BUFFERED, MAX_BATCH);

        pipeline.finish();
        pipeline.offer(new byte[10]);
        pipeline.finish();
        release.countDown();

        await().atMost(5, TimeUnit.SECONDS).until(() -> listener.events.size() == 3);
        assertThat(listener.events).extracting(TranscriptEvent::type).containsExactly(
                TranscriptEvent.Type.FINAL, TranscriptEvent.Type.PARTIAL, TranscriptEvent.Type.FINAL);
    }

    @Test
    void answersAnEndCommandAgainOnceTheEarlierOneHasBeenAnswered() {
        SessionPipeline pipeline = new SessionPipeline(session, executor, listener, MAX_BUFFERED, MAX_BATCH);

        pipeline.finish();
        await().atMost(5, TimeUnit.SECONDS).until(() -> listener.events.size() == 1);
        pipeline.finish();

        await().atMost(5, TimeUnit.SECONDS).until(() -> listener.events.size() == 2);
    }

    @Test
    void reportsAnErrorThrownByTheEngineAsAFailureInsteadOfGoingSilent() {
        session.error = new UnsatisfiedLinkError("native library vanished");
        SessionPipeline pipeline = new SessionPipeline(session, executor, listener, MAX_BUFFERED, MAX_BATCH);

        pipeline.offer(new byte[10]);

        await().atMost(5, TimeUnit.SECONDS).until(() -> listener.failures.size() == 1);
        assertThat(listener.failures.get(0)).isSameAs(session.error);
        pipeline.close();
        await().atMost(5, TimeUnit.SECONDS).until(() -> session.closeCount.get() == 1);
    }

    @Test
    void tellsTheListenerOnceAfterTheSessionHasBeenClosed() throws Exception {
        List<Integer> closeCountSeen = Collections.synchronizedList(new ArrayList<>());
        listener.onClosedHook = () -> closeCountSeen.add(session.closeCount.get());
        SessionPipeline pipeline = new SessionPipeline(session, executor, listener, MAX_BUFFERED, MAX_BATCH);

        pipeline.offer(new byte[10]);
        pipeline.close();
        pipeline.close();

        await().atMost(5, TimeUnit.SECONDS).until(() -> listener.closed.get() >= 1);
        Thread.sleep(100);
        assertThat(listener.closed.get()).isEqualTo(1);
        assertThat(closeCountSeen).as("the session was already closed when the listener heard").containsExactly(1);
    }

    @Test
    void closesTheSessionAndTellsTheListenerWhenTheExecutorHasAlreadyShutDown() {
        executor.close();
        SessionPipeline pipeline = new SessionPipeline(session, executor, listener, MAX_BUFFERED, MAX_BATCH);

        pipeline.offer(new byte[10]);

        assertThat(session.closeCount.get()).isEqualTo(1);
        assertThat(listener.closed.get()).isEqualTo(1);
        assertThat(listener.failures).hasSize(1);
    }

    @Test
    void keepsFeedingTheSessionAfterTheEngineRejectsOneInput() {
        session.failure = new IllegalArgumentException("Utterance is longer than the 60 s limit");
        SessionPipeline pipeline = new SessionPipeline(session, executor, listener, MAX_BUFFERED, MAX_BATCH);

        pipeline.offer(new byte[10]);
        await().atMost(5, TimeUnit.SECONDS).until(() -> listener.rejections.size() == 1);
        session.failure = null;
        pipeline.offer(new byte[10]);

        await().atMost(5, TimeUnit.SECONDS).until(() -> session.receivedBytes() == 10);
        assertThat(listener.failures).as("a rejected input is not a failed session").isEmpty();
        assertThat(listener.rejections.get(0)).hasMessageContaining("60 s limit");
    }

    @Test
    void reportsAFailedTranscriptAsRejectedAndStaysUsable() {
        session.finishFailure = new SpeechRecognitionException("Native model timed out after 300000 ms");
        SessionPipeline pipeline = new SessionPipeline(session, executor, listener, MAX_BUFFERED, MAX_BATCH);

        pipeline.finish();
        await().atMost(5, TimeUnit.SECONDS).until(() -> listener.rejections.size() == 1);
        session.finishFailure = null;
        pipeline.finish();

        await().atMost(5, TimeUnit.SECONDS).until(() -> listener.events.size() == 1);
        assertThat(listener.events.get(0)).isEqualTo(TranscriptEvent.finalResult("done"));
        assertThat(listener.failures).isEmpty();
    }

    @Test
    void alternatesBetweenSessionsSoNoneStarvesTheOthers() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        RecordingSession first = new RecordingSession();
        RecordingSession second = new RecordingSession();
        first.onAccept = () -> order.add("first");
        second.onAccept = () -> order.add("second");
        CountDownLatch release = blockWorkers(1);
        SessionPipeline a = new SessionPipeline(first, executor, new RecordingListener(), MAX_BUFFERED, 10);
        SessionPipeline b = new SessionPipeline(second, executor, new RecordingListener(), MAX_BUFFERED, 10);

        for (int i = 0; i < 5; i++) {
            a.offer(new byte[10]);
        }
        for (int i = 0; i < 5; i++) {
            b.offer(new byte[10]);
        }
        release.countDown();

        await().atMost(5, TimeUnit.SECONDS).until(() -> order.size() == 10);
        assertThat(order).containsExactly(
                "first", "second", "first", "second", "first", "second", "first", "second", "first", "second");
    }

    private CountDownLatch blockWorkers(int workers) {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(workers);
        for (int i = 0; i < workers; i++) {
            executor.execute(() -> {
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return release;
    }

    private static byte[] chunkWithIndex(int index) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) index).array();
    }
}
