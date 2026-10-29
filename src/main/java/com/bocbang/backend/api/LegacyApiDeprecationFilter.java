package com.bocbang.backend.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tells clients still on the unversioned {@code /api/stt} that {@code /api/v1/stt} replaces it (RFC 9745
 * {@code Deprecation} and the {@code successor-version} link). No removal date is promised, only the direction.
 */
@Component
class LegacyApiDeprecationFilter extends OncePerRequestFilter {

    private static final String LEGACY = "/api/stt";
    private static final String CURRENT = "/api/v1/stt";
    /** 2026-10-29, the day {@code /api/v1} shipped, as a Structured Fields date. */
    private static final String DEPRECATED_SINCE = "@1793232000";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = path(request);
        return !(path.equals(LEGACY) || path.startsWith(LEGACY + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Deprecation", DEPRECATED_SINCE);
        response.setHeader("Link", "<" + CURRENT + path(request).substring(LEGACY.length()) + ">; rel=\"successor-version\"");
        chain.doFilter(request, response);
    }

    /** The path the container routes on, so path parameters in the URI cannot hide a legacy request. */
    private static String path(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return request.getServletPath() + (pathInfo == null ? "" : pathInfo);
    }
}
