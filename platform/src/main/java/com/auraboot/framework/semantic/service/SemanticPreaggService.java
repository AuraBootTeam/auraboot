package com.auraboot.framework.semantic.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.semantic.dto.SemanticQueryResponse;
import com.auraboot.framework.semantic.entity.AbSemanticPreagg;
import com.auraboot.framework.semantic.exception.SemanticValidationException;
import com.auraboot.framework.semantic.mapper.AbSemanticPreaggMapper;
import com.auraboot.framework.semantic.mapper.AbSemanticModelMapper;
import com.auraboot.framework.semantic.entity.AbSemanticModel;
import com.auraboot.framework.userattribute.service.UserAttributeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pre-aggregation engine for published semantic models (BI rectification R3).
 *
 * <p>A preagg compiles one governed semantic query (a metric by dimensions,
 * with the model's access scope and the creator's permissions) into a
 * PostgreSQL materialized view via {@link SemanticQueryService#explainQuery}
 * — the exact SQL the live pipeline would run, parameters inlined. Refresh is
 * a scheduled sweep honouring per-preagg intervals; staleness
 * (last_refreshed_at) is exposed so dashboards can annotate freshness.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticPreaggService {

    private final AbSemanticPreaggMapper preaggMapper;
    private final AbSemanticModelMapper modelMapper;
    private final SemanticQueryService queryService;
    private final UserAttributeService userAttributeService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    // ==================== CRUD ====================

    public AbSemanticPreagg create(String name, String semanticModelPid, String metricCode,
                                   List<String> dimensionCodes, int refreshMinutes) {
        return new TransactionTemplate(transactionManager).execute(status ->
                createInTransaction(name, semanticModelPid, metricCode, dimensionCodes, refreshMinutes));
    }

    private AbSemanticPreagg createInTransaction(String name, String semanticModelPid, String metricCode,
                                                List<String> dimensionCodes, int refreshMinutes) {
        Long tenantId = MetaContext.get().getTenantId();
        Long userId = MetaContext.get().getUserId();
        if (refreshMinutes < 1) {
            throw new SemanticValidationException("SEMANTIC_PREAGG_INVALID", "refreshMinutes must be >= 1");
        }
        AbSemanticModel model = modelMapper.findByPid(tenantId, semanticModelPid);
        if (model == null) {
            throw new SemanticValidationException("SEMANTIC_PREAGG_MODEL_MISSING",
                    "Semantic model not found: " + semanticModelPid);
        }

        AbSemanticPreagg preagg = new AbSemanticPreagg();
        preagg.setPid(UniqueIdGenerator.generate());
        preagg.setTenantId(tenantId);
        preagg.setName(name);
        preagg.setSemanticModelPid(semanticModelPid);
        preagg.setMetricCode(metricCode);
        preagg.setDimensionCodes(toJson(dimensionCodes));
        preagg.setRefreshMinutes(refreshMinutes);
        preagg.setMvName("mv_semantic_preagg_" + preagg.getPid().toLowerCase());
        preagg.setCreatedBy(userId);
        preagg.setCreatedAt(OffsetDateTime.now());
        preagg.setUpdatedAt(OffsetDateTime.now());
        preagg.setDeletedFlag(false);

        // Compile + create the materialized view; a preagg without a working
        // MV is not persisted.
        refreshInTransaction(preagg);
        preaggMapper.insert(preagg);
        return preagg;
    }

    public List<AbSemanticPreagg> list() {
        Long tenantId = MetaContext.get().getTenantId();
        return preaggMapper.listByTenant(tenantId);
    }

    public void delete(String pid) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> deleteInTransaction(pid));
    }

    private void deleteInTransaction(String pid) {
        Long tenantId = MetaContext.get().getTenantId();
        AbSemanticPreagg preagg = requirePreagg(tenantId, pid);
        preagg.setDeletedFlag(true);
        preagg.setUpdatedAt(OffsetDateTime.now());
        preaggMapper.updateById(preagg);
        jdbc.execute("DROP MATERIALIZED VIEW IF EXISTS " + preagg.getMvName());
    }

    // ==================== Refresh ====================

    /** Refresh one preagg now; returns rows in the refreshed MV. */
    public long refreshNow(String pid) {
        Long tenantId = MetaContext.get().getTenantId();
        AbSemanticPreagg preagg = requirePreagg(tenantId, pid);
        return refresh(preagg);
    }

    /** Scheduler sweep: refresh every preagg whose interval has elapsed. */
    @Scheduled(fixedDelay = 60_000)
    public void refreshAllDue() {
        // Cross-tenant scan on a @Scheduled thread; per-row execution below binds
        // its own tenant context. Explicit scope (tenant-exemption cleanup W3e
        // follow-up; the top thrower in the W5 runtime census — 609 failures).
        List<AbSemanticPreagg> dueList = MetaContext.runWithoutTenantFilter(
                () -> preaggMapper.listAllAcrossTenants());
        for (AbSemanticPreagg preagg : dueList) {
            OffsetDateTime due = (preagg.getLastRefreshedAt() == null ? preagg.getUpdatedAt()
                    : preagg.getLastRefreshedAt())
                    .plusMinutes(preagg.getRefreshMinutes() == null ? 60 : preagg.getRefreshMinutes());
            if (due.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) continue;
            try {
                refresh(preagg);
            } catch (Exception e) {
                // Each preagg is independent; retain the failure without stopping other tenants.
                log.warn("Semantic preagg {} refresh failed", preagg.getPid(), e);
            }
        }
    }

    /** Compile the governed query, inline its params, and rebuild the MV. */
    private long refresh(AbSemanticPreagg preagg) {
        // Programmatic boundary covers scheduler/self-invocation as well as API calls.
        // PostgreSQL DDL and the MyBatis metadata writes must commit or roll back together.
        return new TransactionTemplate(transactionManager).execute(status -> refreshInTransaction(preagg));
    }

    private long refreshInTransaction(AbSemanticPreagg preagg) {
        AbSemanticModel model = modelMapper.findByPid(preagg.getTenantId(), preagg.getSemanticModelPid());
        if (model == null) {
            throw new SemanticValidationException("SEMANTIC_PREAGG_MODEL_MISSING",
                    "Semantic model not found: " + preagg.getSemanticModelPid());
        }
        MetaContext.Snapshot previous = MetaContext.snapshot();
        MetaContext.clear();
        MetaContext.setContext(preagg.getTenantId(), preagg.getCreatedBy(),
                "semantic-preagg", "semantic-preagg-refresher");
        try {
            SemanticQueryRequest request = new SemanticQueryRequest();
            request.setMetrics(List.of(model.getCode() + "." + preagg.getMetricCode()));
            request.setDimensions(qualifiedDimensions(model, preagg));
            UserContext creator = new UserContext(preagg.getCreatedBy(), preagg.getTenantId(),
                    userAttributeService.getAttributes(preagg.getTenantId(), preagg.getCreatedBy()));
            SemanticQueryResponse explained = queryService.explainQuery(request, creator);

            String selectSql = inlineParams(explained.getSql(), explained.getParams());
            jdbc.execute("DROP MATERIALIZED VIEW IF EXISTS " + preagg.getMvName());
            jdbc.execute("CREATE MATERIALIZED VIEW " + preagg.getMvName() + " AS ("
                    + selectSql.replace(";", "") + ")");

            Long rows = jdbc.queryForObject(
                    "SELECT count(*) FROM " + preagg.getMvName(), Long.class);
            preagg.setLastRefreshedAt(OffsetDateTime.now(ZoneOffset.UTC));
            preagg.setLastRefreshRows(rows == null ? 0 : rows);
            preagg.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            if (preaggMapper.findByPid(preagg.getTenantId(), preagg.getPid()) != null) {
                preaggMapper.updateById(preagg);
            }
            return preagg.getLastRefreshRows();
        } finally {
            MetaContext.clear();
            MetaContext.restore(previous);
        }
    }

    private List<String> qualifiedDimensions(AbSemanticModel model, AbSemanticPreagg preagg) {
        List<String> dims = fromJson(preagg.getDimensionCodes());
        return dims.stream().map(d -> model.getCode() + "." + d).toList();
    }

    /**
     * Inline the compiled query's bind parameters. Values come from the
     * compiler (tenant id, resolved time-range bounds), not user input, but
     * string values are still quoted defensively.
     */
    private String inlineParams(String sql, List<Object> params) {
        if (params == null || params.isEmpty()) return sql;
        String[] literals = params.stream().map(this::toLiteral).toArray(String[]::new);
        StringBuilder out = new StringBuilder(sql.length() + 64);
        boolean inString = false;
        int paramIndex = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') inString = !inString;
            if (c == '?' && !inString && paramIndex < literals.length) {
                out.append(literals[paramIndex++]);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private String toLiteral(Object value) {
        if (value == null) return "NULL";
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        String text = String.valueOf(value);
        return "'" + text.replace("'", "''") + "'";
    }

    private List<String> fromJson(String json) {
        try {
            List<String> dimensions = objectMapper.readValue(json == null ? "[]" : json,
                    new TypeReference<List<String>>() {});
            if (dimensions == null || dimensions.stream().anyMatch(d -> d == null || d.isBlank())) {
                throw new SemanticValidationException("SEMANTIC_PREAGG_INVALID", "Invalid dimension definitions");
            }
            return dimensions;
        } catch (JsonProcessingException e) {
            throw new SemanticValidationException("SEMANTIC_PREAGG_INVALID", "Invalid dimension definitions", e);
        }
    }

    private String toJson(List<String> codes) {
        try {
            return objectMapper.writeValueAsString(codes == null ? List.of() : codes);
        } catch (JsonProcessingException e) {
            throw new SemanticValidationException("SEMANTIC_PREAGG_INVALID", "Cannot encode dimension definitions", e);
        }
    }

    private AbSemanticPreagg requirePreagg(Long tenantId, String pid) {
        AbSemanticPreagg preagg = preaggMapper.findByPid(tenantId, pid);
        if (preagg == null) {
            throw new SemanticValidationException("SEMANTIC_PREAGG_MISSING", "Preagg not found: " + pid);
        }
        return preagg;
    }
}
