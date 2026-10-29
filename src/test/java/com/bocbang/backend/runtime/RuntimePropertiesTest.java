package com.bocbang.backend.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.bocbang.backend.TestSettings;
import org.junit.jupiter.api.Test;

class RuntimePropertiesTest {

    @Test
    void sizesTheStreamLimitFromWhatTheEngineSaysOneWorkerCanSustain() {
        RuntimeProperties.Pool pool = TestSettings.runtime("pool.workers", "4").pool();

        assertThat(pool.resolvedMaxSessions(1)).isEqualTo(4);
        assertThat(pool.resolvedMaxSessions(3)).isEqualTo(12);
    }

    @Test
    void anExplicitStreamLimitWinsOverTheEngineHint() {
        RuntimeProperties.Pool pool = TestSettings.runtime("pool.workers", "4", "pool.max-sessions", "5").pool();

        assertThat(pool.resolvedMaxSessions(3)).isEqualTo(5);
    }

    @Test
    void usesOneWorkerLessThanTheCpuCountWhenNotConfigured() {
        int cpus = Runtime.getRuntime().availableProcessors();

        assertThat(TestSettings.runtime().pool().resolvedWorkers()).isEqualTo(Math.max(1, cpus - 1));
    }
}
