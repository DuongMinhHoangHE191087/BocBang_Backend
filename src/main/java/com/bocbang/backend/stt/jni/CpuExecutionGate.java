package com.bocbang.backend.stt.jni;

import com.bocbang.backend.stt.SpeechRecognitionException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Serializes CPU-bound native calls across VAD, ASR, and the Qwen refiner in fair FIFO order, and
 * flips the C++ abort flag if the calling thread is interrupted or the call exceeds its timeout.
 */
@Component
public class CpuExecutionGate implements AutoCloseable {

    private final ReentrantLock lock = new ReentrantLock(true);
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bocbang-jni-watchdog");
        t.setDaemon(true);
        return t;
    });

    public <T> T call(Duration timeout, Runnable abortNative, Supplier<T> action) {
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpeechRecognitionException("Interrupted while waiting for CPU slot", e);
        }
        Thread caller = Thread.currentThread();
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> timeoutTask = null;
        ScheduledFuture<?> interruptWatcher = null;
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
                throw new SpeechRecognitionException(
                        "Native model timed out after " + timeout.toMillis() + " ms", e);
            }
            if ("ABORTED".equals(e.getMessage())) {
                caller.interrupt();
                throw new SpeechRecognitionException("Native model execution was cancelled", e);
            }
            throw e;
        } finally {
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
            if (interruptWatcher != null) {
                interruptWatcher.cancel(false);
            }
            lock.unlock();
        }
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
    }
}
