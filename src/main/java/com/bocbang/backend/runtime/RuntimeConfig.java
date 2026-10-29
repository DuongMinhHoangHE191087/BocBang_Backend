package com.bocbang.backend.runtime;

import com.bocbang.backend.stt.SpeechToTextEngine;
import com.bocbang.backend.stt.SttProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RuntimeConfig {

    /**
     * Takes the engine only to be created after it, which makes Spring destroy it first: on shutdown the pool still
     * drains queued session closes, and those need the engine's native resources.
     */
    @Bean(destroyMethod = "close")
    InferenceExecutor inferenceExecutor(SttProperties properties, MeterRegistry meters, SpeechToTextEngine engine) {
        return new InferenceExecutor(properties.pool().resolvedWorkers(), meters);
    }

    @Bean
    SessionLimiter sessionLimiter(SttProperties properties, SpeechToTextEngine engine, MeterRegistry meters) {
        return new SessionLimiter(properties.pool().resolvedMaxSessions(engine.sessionsPerWorker()), meters);
    }
}
