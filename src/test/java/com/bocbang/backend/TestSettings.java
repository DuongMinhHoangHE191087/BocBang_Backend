package com.bocbang.backend;

import com.bocbang.backend.stt.SttProperties;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Builds {@link SttProperties} with production defaults, overriding keys given as name/value pairs. */
public final class TestSettings {

    private TestSettings() {
    }

    /** Example: {@code properties("pool.workers", "2", "session.max-buffered-audio", "1s")}. */
    public static SttProperties properties(String... namesAndValues) {
        Map<String, Object> source = new HashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            source.put("stt." + namesAndValues[i], namesAndValues[i + 1]);
        }
        return new Binder(new MapConfigurationPropertySource(source)).bindOrCreate("stt", SttProperties.class);
    }
}
