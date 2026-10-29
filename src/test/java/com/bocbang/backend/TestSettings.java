package com.bocbang.backend;

import com.bocbang.backend.runtime.RuntimeProperties;
import com.bocbang.backend.stt.SttProperties;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Builds {@link SttProperties} and {@link RuntimeProperties} with production defaults, overriding keys given as name/value pairs. */
public final class TestSettings {

    private TestSettings() {
    }

    /** Model and job settings. Example: {@code properties("sample-rate", "8000")}. */
    public static SttProperties properties(String... namesAndValues) {
        return binder(namesAndValues).bindOrCreate("stt", SttProperties.class);
    }

    /** Concurrency and WebSocket settings. Example: {@code runtime("pool.workers", "2", "session.max-buffered-audio", "1s")}. */
    public static RuntimeProperties runtime(String... namesAndValues) {
        return binder(namesAndValues).bindOrCreate("stt", RuntimeProperties.class);
    }

    private static Binder binder(String... namesAndValues) {
        Map<String, Object> source = new HashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            source.put("stt." + namesAndValues[i], namesAndValues[i + 1]);
        }
        return new Binder(new MapConfigurationPropertySource(source));
    }
}
