package com.auraboot.framework.agent.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.NamedQueryTestRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.NamedQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
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
 *
 * <h3>Tenant isolation (C1 fix)</h3>
 * This invoker runs from a scheduler pool thread where {@link MetaContext}
 * is unset/stale. Two guards:
 * <ol>
 *   <li>Verify the resolved named query's owner tenant equals the draft
 *       tenant — refuse execution with {@code status=tenant_mismatch} if
 *       they differ.</li>
 *   <li>Pin {@code MetaContext.currentTenantId} to the draft tenant for the
 *       duration of the call, and clear it in {@code finally}.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NamedQueryShadowInvoker implements ShadowToolInvoker {

    private static final int SHADOW_PAGE_SIZE = 50;

    private final NamedQueryService namedQueryService;
    private final JdbcTemplate jdbcTemplate;

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

        // C1 guard 1: verify the named query belongs to the draft tenant
        // before delegating to NamedQueryService (which reads MetaContext).
        List<Map<String, Object>> ownerRows = jdbcTemplate.queryForList(
                "SELECT tenant_id FROM ab_named_query WHERE code = ?", queryCode);
        if (ownerRows.isEmpty()) {
            log.debug("NamedQueryShadowInvoker: named query not found: {}", queryCode);
            return Map.of("status", "query_not_found", "query_code", queryCode);
        }
        Object ownerTenantObj = ownerRows.get(0).get("tenant_id");
        Long ownerTenantId = ownerTenantObj == null ? null : ((Number) ownerTenantObj).longValue();
        if (tenantId == null || !tenantId.equals(ownerTenantId)) {
            log.warn("NamedQueryShadowInvoker: tenant mismatch — draft tenant {} vs query {} owner {}",
                    tenantId, queryCode, ownerTenantId);
            return Map.of("status", "tenant_mismatch", "query_code", queryCode);
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

        // C1 guard 2: pin MetaContext tenant — NamedQueryService reads it via ThreadLocal.
        // Scheduler threads inherit stale/empty context from the pool.
        boolean hadContext = MetaContext.exists();
        MetaContext.setCurrentTenantId(tenantId);
        try {
            PaginationResult<Map<String, Object>> result = namedQueryService.executeQuery(queryCode, req);
            Map<String, Object> out = new HashMap<>();
            out.put("query_code", queryCode);
            Long total = result == null ? null : result.getTotal();
            out.put("total", total == null ? 0L : total);
            java.util.List<Map<String, Object>> rows = result == null ? null : result.getRecords();
            out.put("rows", rows == null ? java.util.List.of() : rows);
            return out;
        } finally {
            if (!hadContext) {
                MetaContext.clear();
            }
        }
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
