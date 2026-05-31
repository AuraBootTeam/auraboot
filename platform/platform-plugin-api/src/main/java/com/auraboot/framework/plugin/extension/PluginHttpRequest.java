package com.auraboot.framework.plugin.extension;

import java.util.List;
import java.util.Map;

/**
 * Platform-neutral view of an inbound HTTP request handed to a {@link RestEndpointExtension}.
 * Deliberately does NOT expose jakarta.servlet types so plugins stay container-agnostic.
 */
public interface PluginHttpRequest {

    /** HTTP method, upper-case (e.g. "GET"). */
    String method();

    /** Full request path as received (e.g. "/api/plugins/probe/whoami"). */
    String path();

    /** Path variables resolved from the matched route's pathPattern. */
    Map<String, String> pathVars();

    /** Query parameters (a key may have multiple values). */
    Map<String, List<String>> query();

    /** A single request header value, or null when absent. */
    String header(String name);

    /** Raw request body bytes (empty array when no body). */
    byte[] body();
}
