package com.bocbang.backend.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SessionLimiterTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void grantsPermitsUpToTheLimitThenRefuses() {
        SessionLimiter limiter = new SessionLimiter(2, meters);

        Optional<SessionLimiter.Permit> first = limiter.tryAcquire();
        Optional<SessionLimiter.Permit> second = limiter.tryAcquire();
        Optional<SessionLimiter.Permit> third = limiter.tryAcquire();

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(third).isEmpty();
        assertThat(limiter.active()).isEqualTo(2);
        assertThat(limiter.isFull()).isTrue();
    }

    @Test
    void closingAPermitTwiceFreesOnlyOneSlot() {
        SessionLimiter limiter = new SessionLimiter(2, meters);
        SessionLimiter.Permit first = limiter.tryAcquire().orElseThrow();
        limiter.tryAcquire().orElseThrow();

        first.close();
        first.close();

        assertThat(limiter.active()).isEqualTo(1);
        assertThat(limiter.tryAcquire()).isPresent();
        assertThat(limiter.tryAcquire()).isEmpty();
    }

    @Test
    void exposesActiveAndRejectedSessionsAsMetrics() {
        SessionLimiter limiter = new SessionLimiter(1, meters);
        limiter.tryAcquire().orElseThrow();
        limiter.tryAcquire();
        limiter.tryAcquire();

        assertThat(meters.get("stt.sessions.active").gauge().value()).isEqualTo(1.0);
        assertThat(meters.get("stt.sessions.rejected").counter().count()).isEqualTo(2.0);
    }
}
