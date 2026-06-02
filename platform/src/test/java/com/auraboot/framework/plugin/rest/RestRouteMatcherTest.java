package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.plugin.extension.RestRoute;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RestRouteMatcherTest {

    @Test
    void staticPath_matchesExactlyWithNoVars() {
        RestRoute r = RestRoute.of("GET", "/whoami", "probe.probe.read");
        Optional<Map<String, String>> vars = RestRouteMatcher.match(r, "GET", "/whoami");
        assertThat(vars).isPresent();
        assertThat(vars.get()).isEmpty();
    }

    @Test
    void methodMismatch_doesNotMatch() {
        RestRoute r = RestRoute.of("GET", "/whoami", "probe.probe.read");
        assertThat(RestRouteMatcher.match(r, "POST", "/whoami")).isEmpty();
    }

    @Test
    void methodIsCaseInsensitive() {
        RestRoute r = RestRoute.of("GET", "/whoami", "probe.probe.read");
        assertThat(RestRouteMatcher.match(r, "get", "/whoami")).isPresent();
    }

    @Test
    void pathVars_areExtracted() {
        RestRoute r = RestRoute.of("POST", "/batches/{batchId}/records", "probe.probe.write");
        Optional<Map<String, String>> vars = RestRouteMatcher.match(r, "POST", "/batches/B1/records");
        assertThat(vars).isPresent();
        assertThat(vars.get()).containsEntry("batchId", "B1");
    }

    @Test
    void multipleVars_areExtractedPositionally() {
        RestRoute r = RestRoute.of("GET", "/a/{x}/b/{y}", "probe.probe.read");
        Optional<Map<String, String>> vars = RestRouteMatcher.match(r, "GET", "/a/1/b/2");
        assertThat(vars).isPresent();
        assertThat(vars.get()).containsEntry("x", "1").containsEntry("y", "2");
    }

    @Test
    void extraSegment_doesNotMatch() {
        RestRoute r = RestRoute.of("GET", "/whoami", "probe.probe.read");
        assertThat(RestRouteMatcher.match(r, "GET", "/whoami/extra")).isEmpty();
    }

    @Test
    void differentPath_doesNotMatch() {
        RestRoute r = RestRoute.of("GET", "/whoami", "probe.probe.read");
        assertThat(RestRouteMatcher.match(r, "GET", "/other")).isEmpty();
    }
}
