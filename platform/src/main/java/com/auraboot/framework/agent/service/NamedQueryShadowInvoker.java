package com.auraboot.framework.agent.service;

import com.auraboot.framework.meta.dto.NamedQueryTestRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.NamedQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Shadow invoker for read-only named queries (tool_ref = {@code dsl.query}
 * or {@code nq_*}). Named queries have no side effects, so "shadow" is
 * just executing them for real with a capped page size and returning
 * the rows for hashing.
 *
 * tool_ref resolution:
 *   - {@code nq_<code>} → query code = {@code <code>}
 *   - {@code dsl.query} → query code comes from args.query_code
 *
 * Args forwarded to {@link NamedQueryService#executeQuery}:
 *   - parameters:  Map&lt;String,Object&gt; passed through verbatim
 *   - pageSize:    capped to 50 to keep shadow runs cheap
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NamedQueryShadowInvoker implements ShadowToolInvoker {

    private static final int SHADOW_PAGE_SIZE = 50;

    private final NamedQueryService namedQueryService;

    @Override
    public boolean supports(String toolRef) {
        if (toolRef == null) return false;
        return toolRef.startsWith("nq_") || "dsl.query".equals(toolRef);
    }

    @Override
    public Map<String, Object> invokeShadow(Long tenantId, String toolRef, Map<String, Object> args) {
        String queryCode = resolveQueryCode(toolRef, args);
        if (queryCode == null || queryCode.isBlank()) {
            log.debug("NamedQueryShadowInvoker: no query code for tool_ref={}", toolRef);
            return Map.of("status", "no_query_code");
        }

        NamedQueryTestRequest req = new NamedQueryTestRequest();
        req.setPage(1);
        req.setSize(SHADOW_PAGE_SIZE);
        if (args != null) {
            Object params = args.get("parameters");
            if (params instanceof Map<?, ?> m) {
                Map<String, Object> coerced = new HashMap<>();
                m.forEach((k, v) -> coerced.put(String.valueOf(k), v));
                req.setParameters(coerced);
            }
        }

        PaginationResult<Map<String, Object>> result = namedQueryService.executeQuery(queryCode, req);
        Map<String, Object> out = new HashMap<>();
        out.put("query_code", queryCode);
        Long total = result == null ? null : result.getTotal();
        out.put("total", total == null ? 0L : total);
        java.util.List<Map<String, Object>> rows = result == null ? null : result.getRecords();
        out.put("rows", rows == null ? java.util.List.of() : rows);
        return out;
    }

    private String resolveQueryCode(String toolRef, Map<String, Object> args) {
        if (toolRef.startsWith("nq_")) {
            return toolRef.substring(3);
        }
        if (args != null) {
            Object v = args.get("query_code");
            if (v instanceof String s) return s;
        }
        return null;
    }
}
