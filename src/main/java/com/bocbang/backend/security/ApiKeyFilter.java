package com.bocbang.backend.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires an API key on {@code /api} and {@code /ws} when {@code stt.security.api-keys} lists any. The health
 * probes and the demo page stay open: probes carry no key, and the page has nothing to protect. The other
 * actuator endpoints (metrics, info) are open too unless {@code stt.security.protect-actuator} is on.
 *
 * <p>A key arrives as {@code Authorization: Bearer <key>} or {@code X-Api-Key: <key>}. A WebSocket from a browser
 * cannot set headers, so for {@code /ws} the {@code apiKey} query parameter is accepted as well; keys in a URL can
 * end up in proxy logs, so prefer the headers wherever the client can send them.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String KEY_HEADER = "X-Api-Key";
    private static final String KEY_PARAMETER = "apiKey";
    private static final String CHALLENGE = "Bearer realm=\"bocbang\"";
    private static final String PROBLEM =
            "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
                    + "\"detail\":\"A valid API key is required\"}";

    private final List<byte[]> keys;
    private final boolean protectActuator;
    private final Counter rejected;

    public ApiKeyFilter(SecurityProperties properties, MeterRegistry meters) {
        this.keys = properties.activeKeys().stream()
                .map(key -> key.getBytes(StandardCharsets.UTF_8))
                .toList();
        this.protectActuator = properties.protectActuator();
        this.rejected = Counter.builder("stt.auth.rejected")
                .description("Requests refused for a missing or wrong API key")
                .register(meters);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return keys.isEmpty()
                || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !isProtected(path(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = presentedKey(request);
        if (presented != null && matches(presented)) {
            chain.doFilter(request, response);
            return;
        }
        rejected.increment();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
        if (!isWebSocket(path(request))) {
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(PROBLEM);
        }
    }

    /**
     * The path the container routes on. {@code getRequestURI()} still carries path parameters such as
     * {@code /api;x=1/...}, which the container strips before mapping, so it would let them slip past the check.
     */
    private static String path(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return request.getServletPath() + (pathInfo == null ? "" : pathInfo);
    }

    private boolean isProtected(String path) {
        return path.equals("/api") || path.startsWith("/api/") || isWebSocket(path)
                || (protectActuator && isActuatorBeyondHealth(path));
    }

    /** Probes must stay reachable without a key, so only the other endpoints (metrics, info) are covered. */
    private static boolean isActuatorBeyondHealth(String path) {
        boolean actuator = path.equals("/actuator") || path.startsWith("/actuator/");
        boolean health = path.equals("/actuator/health") || path.startsWith("/actuator/health/");
        return actuator && !health;
    }

    private static boolean isWebSocket(String path) {
        return path.equals("/ws") || path.startsWith("/ws/");
    }

    private static String presentedKey(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        String header = request.getHeader(KEY_HEADER);
        if (header != null) {
            return header.trim();
        }
        return isWebSocket(path(request)) ? request.getParameter(KEY_PARAMETER) : null;
    }

    /** Compares against every key without stopping at the first hit, so timing does not reveal which one matched. */
    private boolean matches(String presented) {
        byte[] candidate = presented.getBytes(StandardCharsets.UTF_8);
        boolean match = false;
        for (byte[] key : keys) {
            match |= MessageDigest.isEqual(candidate, key);
        }
        return match;
    }
}
