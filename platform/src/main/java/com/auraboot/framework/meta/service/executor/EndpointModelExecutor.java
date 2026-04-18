package com.auraboot.framework.meta.service.executor;

import com.auraboot.framework.common.util.PinnedHttpRequests;
import com.auraboot.framework.common.util.SsrfValidator;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.dto.SortField;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Executor for {@code sourceType=endpoint} virtual models.
 *
 * <p>Reads {@code Model.extension.endpointAdapter} ({@link EndpointAdapter}) to
 * determine the list/detail endpoint URLs, HTTP method, response extraction
 * paths, and query-parameter names. List responses are paged by mapping
 * {@link DynamicQueryRequest} to configured query params; detail responses
 * replace {@code {pathParam}} placeholders with the primary-key value.
 *
 * <p><strong>SSRF guard</strong>: endpoint hosts that resolve to loopback,
 * link-local, site-local, any-local, or multicast addresses are hard-rejected
 * before any HTTP call. No allowlist override in phase 1.
 *
 * <p>Phase 1 deliberately omits: retry, circuit breaker, per-tenant credential
 * injection, GraphQL, RPC, body-based (POST/PUT) list queries. Those move to
 * phase 2 on a dedicated HTTP call-site abstraction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EndpointModelExecutor implements ModelDataExecutor {

    private static final int CONNECT_TIMEOUT_SECONDS = 5;
    private static final int READ_TIMEOUT_SECONDS = 30;

    /**
     * Shared pinned-IP HTTP client (P3-E DNS-rebinding hardening). JDK
     * {@link HttpClient} is what {@link PinnedHttpRequests} targets for
     * pinning the validated IP at connect time.
     */
    private static final HttpClient PINNED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .build();

    private final MetaModelService metaModelService;
    private final ObjectMapper objectMapper;

    @Override
    public String sourceType() {
        return "endpoint";
    }

    @Override
    public PaginationResult<Map<String, Object>> list(String modelCode, DynamicQueryRequest request) {
        ModelDefinition def = requireVirtualModel(modelCode);
        EndpointAdapter adapter = readAdapter(def);
        EndpointAdapter.ListChannel list = adapter.getList();
        if (list == null || list.getEndpoint() == null || list.getEndpoint().isBlank()) {
            throw new MetaServiceException(
                "endpoint virtual model missing list channel: " + modelCode);
        }
        validateEndpointUrl(list.getEndpoint());

        URI uri = buildListUri(list, request);
        String method = list.getMethod() == null ? "GET" : list.getMethod().toUpperCase();
        log.debug("EndpointModelExecutor.list {} {}", method, uri);

        String responseBody = sendPinned(modelCode, method, uri, "list");

        JsonNode root = readJson(responseBody, modelCode);
        JsonNode items = root.at(toJsonPtr(list.getResponseItemsPath()));
        if (!items.isArray()) {
            throw new MetaServiceException(
                "endpoint list response items at '" + list.getResponseItemsPath()
                    + "' not an array for model " + modelCode);
        }

        List<Map<String, Object>> records = new ArrayList<>(items.size());
        for (JsonNode n : items) {
            records.add(objectMapper.convertValue(n, new TypeReference<Map<String, Object>>() {}));
        }

        JsonNode totalNode = root.at(toJsonPtr(list.getResponseTotalPath()));
        long total = totalNode.isNumber() ? totalNode.asLong() : records.size();

        int pageNum = (request != null && request.getPageNum() != null && request.getPageNum() > 0)
            ? request.getPageNum() : 1;
        int pageSize = (request != null && request.getPageSize() != null && request.getPageSize() > 0)
            ? request.getPageSize() : records.size();

        return PaginationResult.of(records, total, pageNum, pageSize);
    }

    @Override
    public Map<String, Object> get(String modelCode, Object primaryKeyValue) {
        ModelDefinition def = requireVirtualModel(modelCode);
        EndpointAdapter adapter = readAdapter(def);
        EndpointAdapter.DetailChannel detail = adapter.getDetail();
        if (detail == null || detail.getEndpoint() == null || detail.getEndpoint().isBlank()) {
            throw new MetaServiceException(
                "endpoint virtual model missing detail channel: " + modelCode);
        }

        String url = detail.getEndpoint();
        List<String> pathParams = detail.getPathParams();
        if (pathParams != null && !pathParams.isEmpty()) {
            String encoded = primaryKeyValue == null ? ""
                : URLEncoder.encode(primaryKeyValue.toString(), StandardCharsets.UTF_8);
            for (String p : pathParams) {
                url = url.replace("{" + p + "}", encoded);
            }
        }
        validateEndpointUrl(url);

        String method = detail.getMethod() == null ? "GET" : detail.getMethod().toUpperCase();
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new MetaServiceException("endpoint detail URL malformed for model " + modelCode + ": " + url, e);
        }
        log.debug("EndpointModelExecutor.get {} {}", method, uri);

        String responseBody;
        try {
            responseBody = sendPinned(modelCode, method, uri, "detail");
        } catch (EndpointNotFoundException nf) {
            return null;
        }

        JsonNode root = readJson(responseBody, modelCode);
        JsonNode item = root.at(toJsonPtr(detail.getResponseItemPath()));
        if (item.isMissingNode() || item.isNull()) {
            return null;
        }
        return objectMapper.convertValue(item, new TypeReference<Map<String, Object>>() {});
    }

    // --- helpers ---------------------------------------------------------

    /** Internal signal that a detail call returned 404 (maps to null return). */
    private static final class EndpointNotFoundException extends RuntimeException {
        EndpointNotFoundException() { super(); }
    }

    /**
     * Pinned-IP HTTP send. Replaces the previous {@code RestTemplate.exchange}
     * call so the connect-time IP cannot diverge from the IP that passed
     * {@link #validateEndpointUrl(String)} / {@link SsrfValidator}
     * (P3-E #1 DNS rebinding TOCTOU).
     *
     * @param channel diagnostic hint (e.g. "list" / "detail")
     * @throws EndpointNotFoundException when the remote returns 404 on a
     *                                   detail channel so the caller can
     *                                   convert to {@code null}
     */
    private String sendPinned(String modelCode, String method, URI uri, String channel) {
        SsrfValidator.ValidatedTarget target = SsrfValidator.validate(uri.toString());
        if (target == null) {
            throw new MetaServiceException(
                "endpoint " + channel + " target could not be resolved for model " + modelCode);
        }
        HttpRequest.Builder builder = PinnedHttpRequests.newPinnedRequestBuilder(target)
                .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .method(method, HttpRequest.BodyPublishers.noBody());
        try {
            HttpResponse<String> response = PINNED_HTTP_CLIENT.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if ("detail".equals(channel) && status == 404) {
                throw new EndpointNotFoundException();
            }
            if (status >= 400) {
                throw new MetaServiceException(
                    "endpoint " + channel + " call failed (" + status + ") for model " + modelCode);
            }
            return response.body();
        } catch (EndpointNotFoundException | MetaServiceException rethrow) {
            throw rethrow;
        } catch (Exception e) {
            throw new MetaServiceException(
                "endpoint " + channel + " call failed for model " + modelCode + ": " + e.getMessage(), e);
        }
    }

    private ModelDefinition requireVirtualModel(String modelCode) {
        ModelDefinition def = metaModelService.getDefinitionByCode(modelCode);
        if (def == null) {
            throw new MetaServiceException("Model definition not found: " + modelCode);
        }
        if (!"endpoint".equals(def.getSourceType())) {
            throw new MetaServiceException(
                "EndpointModelExecutor invoked for non-endpoint model: " + modelCode);
        }
        return def;
    }

    private EndpointAdapter readAdapter(ModelDefinition def) {
        Map<String, Object> extension = def.getExtension();
        if (extension == null) {
            throw new MetaServiceException(
                "endpoint virtual model missing extension.endpointAdapter: " + def.getCode());
        }
        Object raw = extension.get("endpointAdapter");
        if (raw == null) {
            throw new MetaServiceException(
                "endpoint virtual model missing extension.endpointAdapter: " + def.getCode());
        }
        try {
            return objectMapper.convertValue(raw, EndpointAdapter.class);
        } catch (IllegalArgumentException e) {
            throw new MetaServiceException(
                "malformed endpointAdapter config for model " + def.getCode(), e);
        }
    }

    /** Convert a dotted path like {@code "data.items"} to a JSON Pointer {@code "/data/items"}. */
    private static String toJsonPtr(String dotted) {
        if (dotted == null || dotted.isEmpty()) {
            return "";
        }
        String[] parts = dotted.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append('/').append(p);
        }
        return sb.toString();
    }

    private JsonNode readJson(String body, String modelCode) {
        if (body == null || body.isBlank()) {
            throw new MetaServiceException("endpoint response empty for model " + modelCode);
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new MetaServiceException(
                "endpoint response not valid JSON for model " + modelCode, e);
        }
    }

    private URI buildListUri(EndpointAdapter.ListChannel list, DynamicQueryRequest request) {
        UriComponentsBuilder ub;
        try {
            ub = UriComponentsBuilder.fromUri(new URI(list.getEndpoint()));
        } catch (URISyntaxException e) {
            throw new MetaServiceException("endpoint list URL malformed: " + list.getEndpoint(), e);
        }
        if (request != null) {
            if (request.getPageNum() != null && list.getPageParam() != null) {
                ub.queryParam(list.getPageParam(), request.getPageNum());
            }
            if (request.getPageSize() != null && list.getPageSizeParam() != null) {
                ub.queryParam(list.getPageSizeParam(), request.getPageSize());
            }
            List<SortField> sorts = request.getSortFields();
            if (sorts != null && !sorts.isEmpty()) {
                SortField s0 = sorts.get(0);
                if (s0 != null && s0.getFieldName() != null) {
                    if (list.getSortFieldParam() != null) {
                        ub.queryParam(list.getSortFieldParam(), s0.getFieldName());
                    }
                    if (list.getSortOrderParam() != null) {
                        String dir = s0.getDirection() == SortField.SortDirection.DESC ? "desc" : "asc";
                        ub.queryParam(list.getSortOrderParam(), dir);
                    }
                }
            }
            List<QueryCondition> conditions = request.getConditions();
            if (conditions != null && !conditions.isEmpty()) {
                String mode = list.getFilterParamMode() == null ? "json-array" : list.getFilterParamMode();
                if ("json-array".equals(mode)) {
                    try {
                        ub.queryParam("filters", objectMapper.writeValueAsString(conditions));
                    } catch (Exception e) {
                        throw new MetaServiceException("Failed to serialize filters for endpoint list", e);
                    }
                } else if ("flat".equals(mode)) {
                    for (QueryCondition c : conditions) {
                        if (c != null && c.getFieldName() != null && c.getValue() != null) {
                            ub.queryParam(c.getFieldName(), c.getValue().toString());
                        }
                    }
                } else {
                    throw new MetaServiceException("Unknown filterParamMode: " + mode);
                }
            }
        }
        return ub.build(true).toUri();
    }

    /**
     * SSRF defence: reject URLs whose host resolves to a loopback, link-local,
     * site-local, any-local, or multicast address. No allowlist escape hatch
     * in phase 1 — virtual endpoint models must point to public endpoints.
     */
    static void validateEndpointUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("endpoint URL malformed: " + url, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("endpoint URL must be http/https: " + url);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("endpoint URL missing host: " + url);
        }
        // Textual guard for obvious loopback-ish literals even before DNS lookup.
        String lower = host.toLowerCase();
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.equals("metadata.google.internal")) {
            throw new IllegalArgumentException("endpoint host not allowed: " + host);
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("endpoint host unresolvable: " + host, e);
        }
        for (InetAddress a : addrs) {
            if (a.isLoopbackAddress()
                || a.isLinkLocalAddress()
                || a.isSiteLocalAddress()
                || a.isAnyLocalAddress()
                || a.isMulticastAddress()) {
                throw new IllegalArgumentException(
                    "endpoint host resolves to private/loopback/link-local address: "
                        + host + " -> " + a.getHostAddress());
            }
        }
    }
}
