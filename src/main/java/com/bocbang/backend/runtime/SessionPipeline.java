package com.bocbang.backend.runtime;

import com.bocbang.backend.stt.SpeechRecognitionException;
import com.bocbang.backend.stt.SpeechSession;
import com.bocbang.backend.stt.TranscriptEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs one {@link SpeechSession} on the shared {@link InferenceExecutor}.
 *
 * <p>Any thread may call {@link #offer}, {@link #finish} and {@link #close}; the session itself is only ever used
 * by one worker at a time, in arrival order. Each worker turn handles one batch and then re-queues the session
 * behind the others, so a busy client cannot starve the rest.
 */
public final class SessionPipeline {

    /** Called on an inference thread. */
    public interface Listener {

        /** {@code lagMillis} is how long the oldest audio behind this result waited, including inference. */
        void onTranscript(TranscriptEvent event, long lagMillis);

        /** The engine failed. The pipeline stops feeding the session; the owner should close it. */
        void onFailure(Throwable cause);

        /**
         * The engine refused one input, such as audio past its utterance limit, or could not produce one transcript.
         * The session is still usable, so the pipeline keeps feeding it.
         */
        default void onRejected(RuntimeException cause) {
        }

        /** The session has been closed and nothing will use it again, so what it held can be given back. */
        default void onClosed() {
        }
    }

    private interface Task {
    }

    private record Audio(byte[] pcm, long enqueuedNanos) implements Task {
    }

    private record Finish(long enqueuedNanos) implements Task {
    }

    private static final Task CLOSE = new Task() {
    };

    private final SpeechSession session;
    private final InferenceExecutor executor;
    private final Listener listener;
    private final long maxBufferedBytes;
    private final int maxBatchBytes;

    private final ConcurrentLinkedQueue<Task> queue = new ConcurrentLinkedQueue<>();
    private final AtomicLong bufferedBytes = new AtomicLong();
    private final AtomicBoolean closing = new AtomicBoolean();
    /** Held by whoever may touch the session: set while a drain is scheduled or running. */
    private final AtomicBoolean running = new AtomicBoolean();
    /** True while the newest queued task is a Finish: another end with no audio after it would add nothing. */
    private final AtomicBoolean tailIsFinish = new AtomicBoolean();
    private volatile boolean failed;
    private boolean sessionClosed;

    public SessionPipeline(SpeechSession session, InferenceExecutor executor, Listener listener,
            long maxBufferedBytes, int maxBatchBytes) {
        this.session = session;
        this.executor = executor;
        this.listener = listener;
        this.maxBufferedBytes = maxBufferedBytes;
        this.maxBatchBytes = maxBatchBytes;
    }

    /**
     * Queues audio for recognition. Returns false when the client has more audio waiting than allowed, meaning the
     * server cannot keep up with it. Audio sent after {@link #close} or a failure is dropped and counts as accepted.
     */
    public boolean offer(byte[] pcm) {
        if (closing.get() || failed) {
            return true;
        }
        if (bufferedBytes.addAndGet(pcm.length) > maxBufferedBytes) {
            bufferedBytes.addAndGet(-pcm.length);
            return false;
        }
        queue.add(new Audio(pcm, System.nanoTime()));
        tailIsFinish.set(false);
        schedule();
        return true;
    }

    /**
     * Queues the end of the current utterance; its final transcript goes to the listener after queued audio. Ends
     * that arrive back to back, with no audio between them, share one final transcript.
     */
    public void finish() {
        if (closing.get() || failed) {
            return;
        }
        if (!tailIsFinish.compareAndSet(false, true)) {
            return;
        }
        queue.add(new Finish(System.nanoTime()));
        schedule();
    }

    /** Drops audio that has not started processing and closes the session once nothing is using it. */
    public void close() {
        if (closing.compareAndSet(false, true)) {
            queue.add(CLOSE);
            schedule();
        }
    }

    private void schedule() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::drain);
        } catch (RejectedExecutionException e) {
            // The executor is shutting down. This thread still holds the running flag and keeps it for good, so
            // no worker can touch the session while it is closed here.
            queue.clear();
            bufferedBytes.set(0);
            closeSession();
            if (!closing.get()) {
                failed = true;
                notifyFailure(e);
            }
        }
    }

    private void drain() {
        try {
            Task task = queue.poll();
            if (task instanceof Audio audio) {
                feed(audio);
            } else if (task instanceof Finish finish) {
                if (queue.isEmpty()) {
                    tailIsFinish.set(false);
                }
                finishUtterance(finish);
            } else if (task == CLOSE) {
                queue.clear();
                bufferedBytes.set(0);
                closeSession();
            }
        } finally {
            running.set(false);
            if (!queue.isEmpty()) {
                schedule();
            }
        }
    }

    private void feed(Audio first) {
        int total = first.pcm().length;
        List<Audio> merged = null;
        while (queue.peek() instanceof Audio following && total + following.pcm().length <= maxBatchBytes) {
            queue.poll();
            if (merged == null) {
                merged = new ArrayList<>();
                merged.add(first);
            }
            merged.add(following);
            total += following.pcm().length;
        }
        bufferedBytes.addAndGet(-total);
        if (closing.get() || failed) {
            return;
        }
        byte[] pcm = merged == null ? first.pcm() : concat(merged, total);
        executor.recordQueueWait(System.nanoTime() - first.enqueuedNanos());
        try {
            session.accept(pcm, pcm.length)
                    .ifPresent(event -> listener.onTranscript(event, millisSince(first.enqueuedNanos())));
        } catch (IllegalArgumentException | SpeechRecognitionException e) {
            notifyRejected(e);
        } catch (RuntimeException | Error e) {
            // A native engine fails with Errors (UnsatisfiedLinkError, OutOfMemoryError); the client must still hear it.
            fail(e);
        }
    }

    private void finishUtterance(Finish finish) {
        if (closing.get() || failed) {
            return;
        }
        try {
            TranscriptEvent result = session.finish();
            listener.onTranscript(result, millisSince(finish.enqueuedNanos()));
        } catch (IllegalArgumentException | SpeechRecognitionException e) {
            notifyRejected(e);
        } catch (RuntimeException | Error e) {
            fail(e);
        }
    }

    private void notifyRejected(RuntimeException cause) {
        try {
            listener.onRejected(cause);
        } catch (RuntimeException e) {
            // The listener is the connection; if it cannot take the news it is already closed.
        }
    }

    private void closeSession() {
        if (sessionClosed) {
            return;
        }
        sessionClosed = true;
        try {
            session.close();
        } catch (RuntimeException e) {
            // Nothing left to do for a session that failed to close; the connection is already gone.
        } finally {
            notifyClosed();
        }
    }

    private void notifyClosed() {
        try {
            listener.onClosed();
        } catch (RuntimeException e) {
            // Whatever the listener held is the listener's to clean up; the session is gone either way.
        }
    }

    private void fail(Throwable cause) {
        if (failed) {
            return;
        }
        failed = true;
        notifyFailure(cause);
    }

    private void notifyFailure(Throwable cause) {
        try {
            listener.onFailure(cause);
        } catch (RuntimeException e) {
            // The listener is the connection; if it cannot take the news it is already closed.
        }
    }

    private static long millisSince(long nanos) {
        return Math.max(0, (System.nanoTime() - nanos) / 1_000_000);
    }

    private static byte[] concat(List<Audio> chunks, int total) {
        byte[] merged = new byte[total];
        int offset = 0;
        for (Audio chunk : chunks) {
            System.arraycopy(chunk.pcm(), 0, merged, offset, chunk.pcm().length);
            offset += chunk.pcm().length;
        }
        return merged;
    }
}
