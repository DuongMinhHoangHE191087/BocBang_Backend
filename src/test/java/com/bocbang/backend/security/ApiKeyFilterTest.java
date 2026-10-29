package com.bocbang.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyFilterTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void letsEverythingThroughWhenNoKeyIsConfigured() throws Exception {
        ApiKeyFilter filter = filter();

        Outcome outcome = call(filter, request("/api/v1/stt/status"));

        assertThat(outcome.passed()).isTrue();
        assertThat(outcome.response().getStatus()).isEqualTo(200);
    }

    @Test
    void refusesApiRequestsWithoutAKeyAsAProblemDocument() throws Exception {
        Outcome outcome = call(filter("secret"), request("/api/v1/stt/status"));

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.response().getStatus()).isEqualTo(401);
        assertThat(outcome.response().getHeader("WWW-Authenticate")).startsWith("Bearer");
        assertThat(outcome.response().getContentType()).contains("application/problem+json");
        assertThat(outcome.response().getContentAsString()).contains("\"status\":401");
    }

    @Test
    void acceptsABearerKeyOrAnXApiKeyHeader() throws Exception {
        ApiKeyFilter filter = filter("secret");
        MockHttpServletRequest bearer = request("/api/v1/stt/status");
        bearer.addHeader("Authorization", "Bearer secret");
        MockHttpServletRequest header = request("/api/v1/stt/status");
        header.addHeader("X-Api-Key", "secret");

        assertThat(call(filter, bearer).passed()).isTrue();
        assertThat(call(filter, header).passed()).isTrue();
    }

    @Test
    void refusesAWrongKeyAndCountsTheRefusal() throws Exception {
        MockHttpServletRequest wrong = request("/api/v1/stt/status");
        wrong.addHeader("X-Api-Key", "not-the-key");

        Outcome outcome = call(filter("secret"), wrong);

        assertThat(outcome.response().getStatus()).isEqualTo(401);
        assertThat(meters.get("stt.auth.rejected").counter().count()).isEqualTo(1.0);
    }

    @Test
    void acceptsAnyOfSeveralConfiguredKeys() throws Exception {
        ApiKeyFilter filter = filter("first", "second");
        MockHttpServletRequest request = request("/api/v1/stt/jobs");
        request.addHeader("Authorization", "Bearer second");

        assertThat(call(filter, request).passed()).isTrue();
    }

    @Test
    void neverTreatsABlankConfiguredKeyAsAMatchForAnEmptyHeader() throws Exception {
        ApiKeyFilter filter = filter("", "real");
        MockHttpServletRequest empty = request("/api/v1/stt/status");
        empty.addHeader("Authorization", "Bearer ");
        MockHttpServletRequest emptyXApiKey = request("/api/v1/stt/status");
        emptyXApiKey.addHeader("X-Api-Key", "");

        assertThat(call(filter, empty).response().getStatus()).isEqualTo(401);
        assertThat(call(filter, emptyXApiKey).response().getStatus()).isEqualTo(401);
    }

    @Test
    void staysOffWhenTheOnlyConfiguredKeyIsBlank() throws Exception {
        assertThat(call(filter("  "), request("/api/v1/stt/status")).passed()).isTrue();
    }

    @Test
    void takesTheKeyFromTheQueryOnlyForWebSockets() throws Exception {
        ApiKeyFilter filter = filter("secret");
        MockHttpServletRequest webSocket = request("/ws/stt");
        webSocket.setParameter("apiKey", "secret");
        MockHttpServletRequest rest = request("/api/v1/stt/status");
        rest.setParameter("apiKey", "secret");

        assertThat(call(filter, webSocket).passed()).isTrue();
        assertThat(call(filter, rest).response().getStatus()).isEqualTo(401);
    }

    @Test
    void protectsTheOlderApiPathsAndTheWebSocketPathWithATrailingSlash() throws Exception {
        ApiKeyFilter filter = filter("secret");

        assertThat(call(filter, request("/api/stt/status")).response().getStatus()).isEqualTo(401);
        assertThat(call(filter, request("/ws/stt/")).response().getStatus()).isEqualTo(401);
        assertThat(call(filter, request("/ws/stt")).response().getStatus()).isEqualTo(401);
    }

    @Test
    void leavesProbesMetricsFreeOfTheKeyAndServesTheDemoPage() throws Exception {
        ApiKeyFilter filter = filter("secret");

        assertThat(call(filter, request("/actuator/health/readiness")).passed()).isTrue();
        assertThat(call(filter, request("/")).passed()).isTrue();
        assertThat(call(filter, request("/app.js")).passed()).isTrue();
    }

    @Test
    void judgesThePathTheContainerRoutesOnNotThePathParametersInTheUri() throws Exception {
        MockHttpServletRequest sneaky = request("/api/v1/stt/status");
        sneaky.setRequestURI("/api;x=1/v1/stt/status");

        assertThat(call(filter("secret"), sneaky).response().getStatus()).isEqualTo(401);
    }

    private ApiKeyFilter filter(String... keys) {
        return new ApiKeyFilter(new SecurityProperties(List.of(keys)), meters);
    }

    /** The container resolves the servlet path before filters run, so tests set it the same way. */
    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }

    private static Outcome call(ApiKeyFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Outcome(chain.getRequest() != null, response);
    }

    private record Outcome(boolean passed, MockHttpServletResponse response) {
    }
}
