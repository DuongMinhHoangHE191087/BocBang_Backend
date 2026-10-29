package com.bocbang.backend.runtime;

import com.bocbang.backend.stt.TranscriptEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class RecordingListener implements SessionPipeline.Listener {

    final List<TranscriptEvent> events = new CopyOnWriteArrayList<>();
    final List<Long> lagsMillis = new CopyOnWriteArrayList<>();
    final List<Throwable> failures = new CopyOnWriteArrayList<>();

    @Override
    public void onTranscript(TranscriptEvent event, long lagMillis) {
        events.add(event);
        lagsMillis.add(lagMillis);
    }

    @Override
    public void onFailure(Throwable cause) {
        failures.add(cause);
    }
}
