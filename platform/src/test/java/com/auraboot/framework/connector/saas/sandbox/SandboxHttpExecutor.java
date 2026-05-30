package com.auraboot.framework.connector.saas.sandbox;

import com.auraboot.framework.connector.saas.http.SaasHttpExecutor;
import com.auraboot.framework.connector.saas.http.SaasHttpRequest;
import com.auraboot.framework.connector.saas.http.SaasHttpResponse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * In-process HTTP executor for sandbox contract testing.
 *
 * <p>Accepts a list of {@link Route} entries; each route matches by
 * HTTP method + URL pattern (supports {@code {var}} path placeholders).
 * Requests are dispatched to the first matching route's handler.
 *
 * <p>Fault injection:
 * <ul>
 *   <li>{@link #scheduleFault(int, int)} — inject a single error response
 *       (e.g. 429 with Retry-After) on the next matching request.</li>
 *   <li>{@link #schedule5xx(int)} — inject a 5xx on the next request.</li>
 *   <li>{@link #scheduleAuthExpiry()} — inject a 401 Unauthorized on the
 *       next request, simulating an expired access token.</li>
 * </ul>
 *
 * <p>Observability:
 * <ul>
 *   <li>{@link #getCalledEndpoints()} — ordered list of "{METHOD} {url}"
 *       strings for every call that was dispatched.</li>
 *   <li>{@link #getRequestCount()} — total request count.</li>
 * </ul>
 */
public class SandboxHttpExecutor implements SaasHttpExecutor {

    // -- route model --------------------------------------------------------

    @FunctionalInterface
    public interface RouteHandler {
        SaasHttpResponse handle(SaasHttpRequest request, Map<String, String> pathVars);
    }

    public record Route(String method, String urlPattern, RouteHandler handler) {
        public Route {
            if (method == null || method.isBlank()) throw new IllegalArgumentException("method required");
            if (urlPattern == null || urlPattern.isBlank()) throw new IllegalArgumentException("urlPattern required");
            if (handler == null) throw new IllegalArgumentException("handler required");
        }
    }

    // -- compiled route -----------------------------------------------------

    private record CompiledRoute(String method, Pattern urlRegex, List<String> paramNames, RouteHandler handler) {}

    // -- state --------------------------------------------------------------

    private final List<CompiledRoute> routes;
    private final List<String> calledEndpoints = new ArrayList<>();
    private final AtomicInteger requestCount = new AtomicInteger(0);

    /** Pending fault responses — consumed in FIFO order. */
    private final java.util.Deque<SaasHttpResponse> pendingFaults = new java.util.ArrayDeque<>();

    public SandboxHttpExecutor(List<Route> routes) {
        this.routes = new ArrayList<>();
        for (Route r : routes) {
            this.routes.add(compile(r));
        }
    }

    // -- fault injection ----------------------------------------------------

    /**
     * Schedule a single fault response to be returned on the next request,
     * regardless of which route matches.
     *
     * @param statusCode    HTTP status code (e.g. 429, 503)
     * @param retryAfterSec value for {@code Retry-After} header; 0 = omit
     */
    public void scheduleFault(int statusCode, int retryAfterSec) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        if (retryAfterSec > 0) {
            headers.put("Retry-After", List.of(String.valueOf(retryAfterSec)));
        }
        pendingFaults.add(new SaasHttpResponse(statusCode, headers, emptyBody()));
    }

    /** Schedule a 503 Service Unavailable on the next request. */
    public void schedule5xx(int statusCode) {
        scheduleFault(statusCode, 0);
    }

    /**
     * Schedule a 401 Unauthorized on the next request to simulate an expired
     * access token. The client's retry / refresh path should handle this.
     */
    public void scheduleAuthExpiry() {
        scheduleFault(401, 0);
    }

    // -- observability -------------------------------------------------------

    public List<String> getCalledEndpoints() {
        return Collections.unmodifiableList(calledEndpoints);
    }

    public int getRequestCount() {
        return requestCount.get();
    }

    // -- SaasHttpExecutor ---------------------------------------------------

    @Override
    public SaasHttpResponse execute(SaasHttpRequest request) {
        requestCount.incrementAndGet();
        calledEndpoints.add(request.method() + " " + request.url());

        // Drain a pending fault if one is queued.
        SaasHttpResponse fault = pendingFaults.poll();
        if (fault != null) {
            return fault;
        }

        // Dispatch to the first matching route.
        for (CompiledRoute cr : routes) {
            if (!cr.method().equalsIgnoreCase(request.method())) continue;
            java.util.regex.Matcher m = cr.urlRegex().matcher(stripQuery(request.url()));
            if (!m.matches()) continue;
            Map<String, String> pathVars = new LinkedHashMap<>();
            for (int i = 0; i < cr.paramNames().size(); i++) {
                pathVars.put(cr.paramNames().get(i), m.group(i + 1));
            }
            return cr.handler().handle(request, pathVars);
        }

        // No route matched.
        return new SaasHttpResponse(404, Map.of(),
                ("Sandbox: no route matched " + request.method() + " " + request.url())
                        .getBytes(StandardCharsets.UTF_8));
    }

    // -- helpers ------------------------------------------------------------

    private static CompiledRoute compile(Route r) {
        List<String> names = new ArrayList<>();
        // Manual compile with capture groups for {var} placeholders.
        StringBuilder sb = new StringBuilder();
        String pattern = r.urlPattern();
        int last = 0;
        java.util.regex.Matcher m = Pattern.compile("\\{([^}]+)}").matcher(pattern);
        while (m.find()) {
            sb.append(Pattern.quote(pattern.substring(last, m.start())));
            sb.append("([^/?#]+)");
            names.add(m.group(1));
            last = m.end();
        }
        sb.append(Pattern.quote(pattern.substring(last)));
        return new CompiledRoute(r.method(), Pattern.compile(sb.toString()), names, r.handler());
    }

    private static String stripQuery(String url) {
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }

    private static byte[] emptyBody() {
        return "{}".getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Factory helper — build a JSON 200 response from a string literal.
     */
    public static SaasHttpResponse jsonOk(String json) {
        return new SaasHttpResponse(200, Map.of("Content-Type", List.of("application/json")),
                json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Factory helper — build a JSON response with an arbitrary status code.
     */
    public static SaasHttpResponse jsonStatus(int status, String json) {
        return new SaasHttpResponse(status, Map.of("Content-Type", List.of("application/json")),
                json.getBytes(StandardCharsets.UTF_8));
    }
}
