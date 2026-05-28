package com.auraboot.framework.semantic.service;

import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.semantic.compiler.CompiledQuery;
import com.auraboot.framework.semantic.compiler.MetricCompileException;
import com.auraboot.framework.semantic.compiler.MetricCompiler;
import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.semantic.dto.SemanticModelDTO;
import com.auraboot.framework.semantic.dto.SemanticQueryResponse;
import com.auraboot.framework.semantic.entity.AbSemanticMetric;
import com.auraboot.framework.semantic.entity.AbSemanticModel;
import com.auraboot.framework.semantic.entity.AbSemanticQueryLog;
import com.auraboot.framework.semantic.mapper.AbSemanticMetricMapper;
import com.auraboot.framework.semantic.mapper.AbSemanticModelMapper;
import com.auraboot.framework.semantic.mapper.AbSemanticQueryLogMapper;
import com.auraboot.framework.semantic.parser.SemanticValidator;
import com.auraboot.framework.semantic.parser.SemanticYamlParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Orchestrates the full {@code POST /api/semantic/query} request lifecycle:
 *
 * <pre>
 *   request → resolve model (by metric pid OR explicit modelPid)
 *           → re-parse yaml_source for measures
 *           → MetricCompiler.compile()
 *           → JdbcTemplate.queryForList(sql, params)
 *           → SemanticQueryResponse + audit log
 * </pre>
 *
 * <p>For W4 the request DTO uses {@code String} metric/dimension codes prefixed
 * by {@code <model_code>.}; the service strips the prefix when looking up
 * model + checking permissions.
 *
 * <p>The {@code /sql} debug endpoint short-circuits before {@code execute()}
 * and returns the compiled SQL + params for client inspection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticQueryService {

    private final SemanticYamlParser parser;
    private final SemanticValidator validator;
    private final MetricCompiler compiler;
    private final AbSemanticModelMapper modelMapper;
    private final AbSemanticMetricMapper metricMapper;
    private final AbSemanticQueryLogMapper queryLogMapper;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * JdbcTemplate is optional: in unit tests we mock the mappers and the
     * compiler, so JdbcTemplate may not be wired. {@code execute()} still
     * works for {@code /sql} debug because it skips JdbcTemplate.
     */
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    /** Execute and return rows (POST /api/semantic/query). */
    public SemanticQueryResponse executeQuery(SemanticQueryRequest req,
                                               UserContext user) {
        long t0 = System.nanoTime();
        Compiled c = compile(req, user);
        SemanticQueryResponse out = baseResponse(c);
        if (jdbcTemplate == null) {
            out.getWarnings().add(
                "JdbcTemplate not wired; returning compiled SQL only. "
                + "Wire spring-jdbc bean for live execution.");
            out.setSql(c.compiled.getSql());
            out.setParams(c.compiled.getParams());
            return out;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    c.compiled.getSql(), c.compiled.getParams().toArray());
            out.setRows(rows);
            out.setRowcount(rows.size());
        } catch (Exception e) {
            log.warn("Semantic query execution failed: {}", e.getMessage(), e);
            out.getWarnings().add("execution_failed: " + e.getMessage());
        }
        long durationMs = (System.nanoTime() - t0) / 1_000_000;
        out.setDurationMs(durationMs);

        audit(req, c, out, user);
        return out;
    }

    /** Compile-only (POST /api/semantic/sql) — returns SQL but does NOT execute. */
    public SemanticQueryResponse explainQuery(SemanticQueryRequest req,
                                               UserContext user) {
        Compiled c = compile(req, user);
        SemanticQueryResponse out = baseResponse(c);
        out.setSql(c.compiled.getSql());
        out.setParams(c.compiled.getParams());
        return out;
    }

    /** Validate only — confirms request is compilable. Used by /dry-run. */
    public void validateQuery(SemanticQueryRequest req, UserContext user) {
        compile(req, user);  // throws on any compile error
    }

    // -- helpers -------------------------------------------------------------

    private Compiled compile(SemanticQueryRequest req, UserContext user) {
        SemanticModelDTO model = resolveModel(req, user.tenantId());
        CompiledQuery cq = compiler.compile(model, req, user);
        return new Compiled(model, cq);
    }

    /**
     * Resolve the SemanticModelDTO based on the first metric's prefix
     * ({@code <model_code>.<metric_code>}). v0.1 forbids cross-model queries.
     */
    SemanticModelDTO resolveModel(SemanticQueryRequest req, Long tenantId) {
        if (req.getMetrics() == null || req.getMetrics().isEmpty()) {
            throw new MetricCompileException("UNKNOWN_METRIC",
                    "at least one metric is required");
        }
        String prefix = extractModelPrefix(req.getMetrics().get(0));

        // All metrics must share the prefix in v0.1
        for (String m : req.getMetrics()) {
            if (!extractModelPrefix(m).equals(prefix)) {
                throw new MetricCompileException("CROSS_MODEL_QUERY",
                        "v0.1 does not support cross-model queries: " + m + " vs " + prefix);
            }
        }

        AbSemanticModel row = findActiveModelByCode(tenantId, prefix);
        if (row == null) {
            throw new MetricCompileException("UNKNOWN_METRIC",
                    "no active semantic model with code='" + prefix + "'");
        }
        try {
            // Re-parse stored YAML to recover measures (v0.1 has no measure table).
            SemanticModelDTO model = parser.parse(row.getYamlSource());
            validator.validate(model);
            return model;
        } catch (Exception e) {
            throw new MetricCompileException("MODEL_REF_MISSING",
                    "yaml_source for model " + prefix + " is invalid: " + e.getMessage());
        }
    }

    private AbSemanticModel findActiveModelByCode(Long tenantId, String code) {
        // listActiveByTenant returns ALL; filter by code (small N expected).
        return modelMapper.listActiveByTenant(tenantId).stream()
                .filter(m -> code.equals(m.getCode()))
                .findFirst().orElse(null);
    }

    private String extractModelPrefix(String codeWithPrefix) {
        int dot = codeWithPrefix.indexOf('.');
        if (dot < 0) {
            throw new MetricCompileException("UNKNOWN_METRIC",
                    "metric '" + codeWithPrefix + "' missing <model>.<metric> prefix");
        }
        return codeWithPrefix.substring(0, dot);
    }

    private SemanticQueryResponse baseResponse(Compiled c) {
        SemanticQueryResponse out = new SemanticQueryResponse();
        out.setQueryId(UlidGenerator.generate());
        out.setSqlFingerprint(c.compiled.getSqlFingerprint());
        out.setReferencedColumns(c.compiled.getReferencedColumns());
        return out;
    }

    private void audit(SemanticQueryRequest req, Compiled c,
                        SemanticQueryResponse out, UserContext user) {
        try {
            AbSemanticQueryLog row = new AbSemanticQueryLog();
            row.setQueryId(out.getQueryId());
            row.setTenantId(user.tenantId());
            row.setUserId(user.userId());
            row.setMetricPids(jsonMapper.writeValueAsString(req.getMetrics()));
            row.setDimensionPids(jsonMapper.writeValueAsString(req.getDimensions()));
            row.setFilters(jsonMapper.writeValueAsString(req.getFilters()));
            row.setRowcount(out.getRowcount());
            row.setDurationMs((int) out.getDurationMs());
            row.setCacheHit(out.isCacheHit());
            row.setSqlFingerprint(c.compiled.getSqlFingerprint());
            row.setExecutedAt(Instant.now());
            queryLogMapper.insert(row);
        } catch (Exception e) {
            // Audit MUST NOT fail the request; warn and continue.
            log.warn("Failed to insert semantic query audit log: {}", e.getMessage());
        }
    }

    private record Compiled(SemanticModelDTO model, CompiledQuery compiled) {}
}
