package com.bocbang.backend.stt.jni;

import com.bocbang.backend.stt.SpeechRecognitionException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Serializes CPU-bound native calls across VAD, ASR, and the Qwen refiner in fair FIFO order, and
 * flips the C++ abort flag if the calling thread is interrupted or the call exceeds its timeout.
 */
@Component
public class CpuExecutionGate implements AutoCloseable {

    private final ReentrantLock lock = new ReentrantLock(true);
    private final MeterRegistry meters;
    private final Map<String, AtomicInteger> activeByOperation = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bocbang-jni-watchdog");
        t.setDaemon(true);
        return t;
    });

    public CpuExecutionGate() {
        this(new SimpleMeterRegistry());
    }

    @Autowired
    public CpuExecutionGate(MeterRegistry meters) {
        this.meters = meters;
        Gauge.builder("stt.native.active", lock, l -> l.isLocked() ? 1.0 : 0.0)
                .description("Whether a native C++ model call currently holds the CPU gate")
                .register(meters);
        Gauge.builder("stt.native.queued", lock, ReentrantLock::getQueueLength)
                .description("Threads waiting for the native C++ CPU gate")
                .register(meters);
    }

    public <T> T call(Duration timeout, Runnable abortNative, Supplier<T> action) {
        return call("other", timeout, abortNative, action);
    }

    public <T> T call(String operation, Duration timeout, Runnable abortNative, Supplier<T> action) {
        long waitStarted = System.nanoTime();
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            recordWait(operation, System.nanoTime() - waitStarted);
            Thread.currentThread().interrupt();
            throw new SpeechRecognitionException("Interrupted while waiting for CPU slot", e);
        }
        recordWait(operation, System.nanoTime() - waitStarted);

        AtomicInteger opActive = activeCounter(operation);
        opActive.incrementAndGet();
        Thread caller = Thread.currentThread();
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> timeoutTask = null;
        ScheduledFuture<?> interruptWatcher = null;
        long execStarted = System.nanoTime();
        String outcome = "success";
        try {
            if (abortNative != null) {
                if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                    timeoutTask = watchdog.schedule(() -> {
                        timedOut.set(true);
                        abortNative.run();
                    }, timeout.toMillis(), TimeUnit.MILLISECONDS);
                }
                interruptWatcher = watchdog.scheduleAtFixedRate(() -> {
                    if (caller.isInterrupted()) {
                        abortNative.run();
                    }
                }, 100, 100, TimeUnit.MILLISECONDS);
            }
            return action.get();
        } catch (SpeechRecognitionException e) {
            if (timedOut.get()) {
                outcome = "timeout";
                throw new SpeechRecognitionException(
                        "Native model timed out after " + timeout.toMillis() + " ms", e);
            }
            if ("ABORTED".equals(e.getMessage())) {
                outcome = "cancelled";
                caller.interrupt();
                throw new SpeechRecognitionException("Native model execution was cancelled", e);
            }
            outcome = "error";
            throw e;
        } catch (RuntimeException | Error e) {
            outcome = "error";
            throw e;
        } finally {
            opActive.decrementAndGet();
            recordDuration(operation, outcome, System.nanoTime() - execStarted);
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
            if (interruptWatcher != null) {
                interruptWatcher.cancel(false);
            }
            lock.unlock();
        }
    }

    private AtomicInteger activeCounter(String operation) {
        return activeByOperation.computeIfAbsent(operation, op -> {
            AtomicInteger flag = new AtomicInteger();
            Gauge.builder("stt.native.operation.active", flag, AtomicInteger::get)
                    .description("Whether this native C++ operation is currently executing")
                    .tag("operation", op)
                    .register(meters);
            return flag;
        });
    }

    private void recordWait(String operation, long nanos) {
        Timer.builder("stt.native.wait")
                .description("Time spent waiting for the native C++ CPU gate")
                .tag("operation", operation)
                .register(meters)
                .record(Math.max(0L, nanos), TimeUnit.NANOSECONDS);
    }

    private void recordDuration(String operation, String outcome, long nanos) {
        Timer.builder("stt.native.duration")
                .description("Time spent executing a native C++ JNI call")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(meters)
                .record(Math.max(0L, nanos), TimeUnit.NANOSECONDS);
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
    }
}
