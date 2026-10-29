package com.bocbang.backend.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InferenceExecutorTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void neverRunsMoreTasksAtOnceThanItHasWorkers() throws Exception {
        try (InferenceExecutor executor = new InferenceExecutor(2, meters)) {
            AtomicInteger running = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(8);

            for (int i = 0; i < 8; i++) {
                executor.execute(() -> {
                    peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    running.decrementAndGet();
                    done.countDown();
                });
            }

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(peak.get()).isEqualTo(2);
        }
    }

    @Test
    void runsTasksOnNamedInferenceThreads() throws Exception {
        try (InferenceExecutor executor = new InferenceExecutor(1, meters)) {
            String threadName = executor.call(() -> Thread.currentThread().getName());

            assertThat(threadName).startsWith("stt-infer-");
        }
    }

    @Test
    void callReturnsTheResultAndRethrowsTheFailure() throws Exception {
        try (InferenceExecutor executor = new InferenceExecutor(1, meters)) {
            assertThat(executor.call(() -> 42)).isEqualTo(42);

            assertThatThrownBy(() -> executor.call(() -> {
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class).hasMessage("boom");
        }
    }

    @Test
    void rejectsWorkAfterItIsClosed() {
        InferenceExecutor executor = new InferenceExecutor(1, meters);
        executor.close();

        assertThatThrownBy(() -> executor.execute(() -> { }))
                .isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void recordsHowLongWorkWaitedBeforeRunning() {
        try (InferenceExecutor executor = new InferenceExecutor(1, meters)) {
            executor.recordQueueWait(5_000_000L);

            assertThat(meters.get("stt.queue.wait").timer().count()).isEqualTo(1);
            assertThat(meters.get("stt.inference.workers").gauge().value()).isEqualTo(1.0);
        }
    }
}
