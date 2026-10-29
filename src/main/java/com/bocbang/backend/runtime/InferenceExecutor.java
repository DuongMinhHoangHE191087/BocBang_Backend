package com.bocbang.backend.runtime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The fixed set of threads that runs model inference for every client. CPU-bound work must not run on the web
 * server's threads, and a fixed size keeps total CPU use bounded no matter how many clients connect.
 */
public class InferenceExecutor implements AutoCloseable {

    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    private final ThreadPoolExecutor pool;
    private final int workers;
    private final Timer queueWait;
    private final Timer inference;

    public InferenceExecutor(int workers, MeterRegistry meters) {
        this.workers = workers;
        AtomicInteger threadNumber = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "stt-infer-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        this.queueWait = Timer.builder("stt.queue.wait")
                .description("Time audio waited for a free inference thread")
                .register(meters);
        this.inference = Timer.builder("stt.inference.duration")
                .description("Time spent in one engine call")
                .register(meters);
        Gauge.builder("stt.inference.workers", () -> workers).register(meters);
        Gauge.builder("stt.inference.active", pool, ThreadPoolExecutor::getActiveCount).register(meters);
        Gauge.builder("stt.inference.queued", pool, p -> p.getQueue().size()).register(meters);
    }

    /** Runs the task on an inference thread without waiting. */
    public void execute(Runnable task) {
        pool.execute(() -> inference.record(task));
    }

    /** Runs the task on an inference thread and waits for its result, rethrowing its failure unchanged. */
    public <T> T call(Callable<T> task) throws Exception {
        Future<T> future = pool.submit(() -> inference.recordCallable(task));
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    public void recordQueueWait(long nanos) {
        queueWait.record(nanos, TimeUnit.NANOSECONDS);
    }

    public int workers() {
        return workers;
    }

    public int active() {
        return pool.getActiveCount();
    }

    public int queued() {
        return pool.getQueue().size();
    }

    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
