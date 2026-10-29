package com.bocbang.backend.runtime;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caps how many streams are served at once. Refusing the extra client early keeps everyone already connected
 * at realtime speed instead of letting all of them fall behind.
 */
public class SessionLimiter {

    private final int max;
    private final AtomicInteger active = new AtomicInteger();
    private final Counter rejected;

    public SessionLimiter(int max, MeterRegistry meters) {
        this.max = max;
        Gauge.builder("stt.sessions.active", active, AtomicInteger::get)
                .description("Streams being recognized right now")
                .register(meters);
        Gauge.builder("stt.sessions.max", () -> max)
                .description("Most streams served at once")
                .register(meters);
        this.rejected = Counter.builder("stt.sessions.rejected")
                .description("Streams refused because the server was at capacity")
                .register(meters);
    }

    /** Takes a slot if one is free. Closing the permit gives the slot back. */
    public Optional<Permit> tryAcquire() {
        int current;
        do {
            current = active.get();
            if (current >= max) {
                rejected.increment();
                return Optional.empty();
            }
        } while (!active.compareAndSet(current, current + 1));
        return Optional.of(new Permit());
    }

    public int active() {
        return active.get();
    }

    public int max() {
        return max;
    }

    public boolean isFull() {
        return active.get() >= max;
    }

    public final class Permit implements AutoCloseable {

        private final AtomicBoolean released = new AtomicBoolean();

        private Permit() {
        }

        /** Safe to call more than once. */
        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                active.decrementAndGet();
            }
        }
    }
}
