package com.bocbang.backend.stt;

import static org.assertj.core.api.Assertions.assertThat;

import com.bocbang.backend.TestSettings;
import org.junit.jupiter.api.Test;

class SttPropertiesTest {

    @Test
    void sizesTheStreamLimitFromWhatTheEngineSaysOneWorkerCanSustain() {
        SttProperties.Pool pool = TestSettings.properties("pool.workers", "4").pool();

        assertThat(pool.resolvedMaxSessions(1)).isEqualTo(4);
        assertThat(pool.resolvedMaxSessions(3)).isEqualTo(12);
    }

    @Test
    void anExplicitStreamLimitWinsOverTheEngineHint() {
        SttProperties.Pool pool = TestSettings.properties("pool.workers", "4", "pool.max-sessions", "5").pool();

        assertThat(pool.resolvedMaxSessions(3)).isEqualTo(5);
    }

    @Test
    void usesOneWorkerLessThanTheCpuCountWhenNotConfigured() {
        int cpus = Runtime.getRuntime().availableProcessors();

        assertThat(TestSettings.properties().pool().resolvedWorkers()).isEqualTo(Math.max(1, cpus - 1));
    }
}
