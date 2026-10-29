package com.bocbang.backend.runtime;

import com.bocbang.backend.stt.TranscriptEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

final class RecordingListener implements SessionPipeline.Listener {

    final List<TranscriptEvent> events = new CopyOnWriteArrayList<>();
    final List<Long> lagsMillis = new CopyOnWriteArrayList<>();
    final List<Throwable> failures = new CopyOnWriteArrayList<>();
    final List<RuntimeException> rejections = new CopyOnWriteArrayList<>();
    final AtomicInteger closed = new AtomicInteger();

    volatile Runnable onClosedHook;

    @Override
    public void onTranscript(TranscriptEvent event, long lagMillis) {
        events.add(event);
        lagsMillis.add(lagMillis);
    }

    @Override
    public void onFailure(Throwable cause) {
        failures.add(cause);
    }

    @Override
    public void onRejected(RuntimeException cause) {
        rejections.add(cause);
    }

    @Override
    public void onClosed() {
        Runnable hook = onClosedHook;
        if (hook != null) {
            hook.run();
        }
        closed.incrementAndGet();
    }
}
