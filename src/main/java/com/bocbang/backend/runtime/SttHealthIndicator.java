package com.bocbang.backend.runtime;

import com.bocbang.backend.stt.SpeechToTextEngine;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports BUSY while every stream slot is taken. {@code application.yml} ranks BUSY below UP for the main health
 * endpoint, so a monitor sees a busy node as healthy, and above UP for the readiness group, so a load balancer
 * sends new clients elsewhere. Liveness never includes this indicator.
 */
@Component("stt")
class SttHealthIndicator implements HealthIndicator {

    private final SpeechToTextEngine engine;
    private final SessionLimiter limiter;
    private final InferenceExecutor executor;

    SttHealthIndicator(SpeechToTextEngine engine, SessionLimiter limiter, InferenceExecutor executor) {
        this.engine = engine;
        this.limiter = limiter;
        this.executor = executor;
    }

    @Override
    public Health health() {
        Health.Builder health = limiter.isFull() ? Health.status("BUSY") : Health.up();
        return health
                .withDetail("engine", engine.name())
                .withDetail("activeSessions", limiter.active())
                .withDetail("maxSessions", limiter.max())
                .withDetail("workers", executor.workers())
                .withDetail("queuedTasks", executor.queued())
                .build();
    }
}
