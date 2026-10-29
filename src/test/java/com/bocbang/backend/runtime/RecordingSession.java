package com.bocbang.backend.runtime;

import com.bocbang.backend.stt.SpeechSession;
import com.bocbang.backend.stt.TranscriptEvent;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Session double that records how the pipeline drives it and detects unsafe concurrent use. */
final class RecordingSession implements SpeechSession {

    final AtomicInteger closeCount = new AtomicInteger();
    final AtomicBoolean overlapped = new AtomicBoolean();
    final AtomicBoolean closedWhileBusy = new AtomicBoolean();
    final List<Integer> chunkLengths = new CopyOnWriteArrayList<>();
    final CountDownLatch enteredAccept = new CountDownLatch(1);

    /** When set, {@link #accept} waits on it, so a test can hold the session busy. */
    volatile CountDownLatch gate;
    volatile RuntimeException failure;
    /** Thrown from {@link #accept} like a failure, but as an Error, the way a broken native library behaves. */
    volatile Error error;
    volatile Runnable onAccept;
    /** Thrown from {@link #finish} while set. */
    volatile RuntimeException finishFailure;

    private final AtomicInteger busy = new AtomicInteger();
    private final ByteArrayOutputStream received = new ByteArrayOutputStream();

    @Override
    public Optional<TranscriptEvent> accept(byte[] pcm, int length) {
        enter();
        try {
            enteredAccept.countDown();
            Runnable hook = onAccept;
            if (hook != null) {
                hook.run();
            }
            awaitGate();
            if (error != null) {
                throw error;
            }
            if (failure != null) {
                throw failure;
            }
            chunkLengths.add(length);
            synchronized (received) {
                received.write(pcm, 0, length);
            }
            return Optional.of(TranscriptEvent.partial("chunk-" + chunkLengths.size()));
        } finally {
            busy.decrementAndGet();
        }
    }

    @Override
    public TranscriptEvent finish() {
        enter();
        try {
            if (finishFailure != null) {
                throw finishFailure;
            }
            return TranscriptEvent.finalResult("done");
        } finally {
            busy.decrementAndGet();
        }
    }

    @Override
    public void close() {
        if (busy.get() > 0) {
            closedWhileBusy.set(true);
        }
        closeCount.incrementAndGet();
    }

    int receivedBytes() {
        synchronized (received) {
            return received.size();
        }
    }

    byte[] receivedPayload() {
        synchronized (received) {
            return received.toByteArray();
        }
    }

    private void enter() {
        if (busy.incrementAndGet() > 1) {
            overlapped.set(true);
        }
    }

    private void awaitGate() {
        CountDownLatch latch = gate;
        if (latch == null) {
            return;
        }
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
