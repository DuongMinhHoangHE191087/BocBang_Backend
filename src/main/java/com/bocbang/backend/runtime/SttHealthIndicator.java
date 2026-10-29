package com.bocbang.backend.runtime;

import com.bocbang.backend.stt.SpeechToTextEngine;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Part of the readiness group only: a full server reports OUT_OF_SERVICE so a load balancer sends new clients
 * elsewhere, while liveness stays UP so the process is not restarted just for being busy.
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
        Health.Builder health = limiter.isFull() ? Health.outOfService() : Health.up();
        return health
                .withDetail("engine", engine.name())
                .withDetail("activeSessions", limiter.active())
                .withDetail("maxSessions", limiter.max())
                .withDetail("workers", executor.workers())
                .withDetail("queuedTasks", executor.queued())
                .build();
    }
}
