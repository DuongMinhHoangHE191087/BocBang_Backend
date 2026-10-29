package com.bocbang.backend.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param apiKeys keys accepted on {@code /api} and {@code /ws}. Leave empty to turn authentication off, which
 *     is the default so a board on a private network works without setup.
 * @param protectActuator also require a key on {@code /actuator} except the health probes, so metrics are not
 *     public. Off by default; has no effect while {@code apiKeys} is empty.
 */
@ConfigurationProperties("stt.security")
public record SecurityProperties(@DefaultValue List<String> apiKeys, @DefaultValue("false") boolean protectActuator) {

    /** The configured keys without blank entries, so an empty header can never match a blank key. */
    public List<String> activeKeys() {
        return apiKeys.stream()
                .map(String::trim)
                .filter(key -> !key.isEmpty())
                .toList();
    }
}
