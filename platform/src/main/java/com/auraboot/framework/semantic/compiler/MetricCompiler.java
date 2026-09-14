package com.auraboot.framework.semantic.compiler;

import com.auraboot.framework.semantic.dto.DimensionDTO;
import com.auraboot.framework.semantic.dto.EntityDTO;
import com.auraboot.framework.semantic.dto.MeasureDTO;
import com.auraboot.framework.semantic.dto.MetricDTO;
import com.auraboot.framework.semantic.dto.SemanticModelDTO;
import com.auraboot.framework.semantic.enums.MetricType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles a {@link SemanticQueryRequest} against a resolved
 * {@link SemanticModelDTO} into a parameterised {@link CompiledQuery}.
 *
 * <p>Algorithms follow PRD 16 §7.1–§7.4:
 * <ul>
 *   <li>{@link MetricType#SIMPLE} → {@code SUM(expr) FILTER (WHERE filter)} (or other agg)</li>
 *   <li>{@link MetricType#RATIO} → {@code SUM(num) / NULLIF(SUM(denom), 0)}</li>
 *   <li>{@link MetricType#CUMULATIVE} → window function over primary_time grain</li>
 *   <li>{@link MetricType#DERIVED} → expression composed from other metric placeholders
 *       (substituted with their compiled expression — same level, no CTE in v0.1)</li>
 *   <li>{@link MetricType#CONVERSION} → cohort self-join (v0.2): first base
 *       event per (entity, dims) opens a cohort; the rate counts entities with
 *       a conversion event inside the observation window; immature cohorts
 *       (window not closed at as-of) stay out of the denominator</li>
 * </ul>
 *
 * <p>Hard rules (canonical AGENTS.md):
 * <ul>
 *   <li>Every user-supplied value is bound via {@code ?} — never concatenated.</li>
 *   <li>Identifiers (table/column/alias) are validated against the model
 *       catalogue before emission; unknown codes throw
 *       {@link MetricCompileException}.</li>
 *   <li>{@link AccessPolicyCompiler} runs unconditionally; its {@code AND (...)}
 *       fragments are appended even if the request specifies no filters.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricCompiler {

    private static final Pattern IDENT = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    /** Internal cohort subquery alias names (exact-match collision guard). */
    private static final String COHORT_ENTITY_ALIAS = "amos_ce";
    private static final String COHORT_ENTRY_TIME_ALIAS = "amos_et";
    private static final String COHORT_CONV_ALIAS = "amos_cv";
    /**
     * Mirrors the SemanticYamlValidator / AccessPolicyCompiler denylist. Keep in
     * sync — cohort filters are interpolated into subquery WHERE fragments, so
     * {@code select}/{@code with} would open the cross-tenant exfiltration vector.
     */
    private static final Pattern COHORT_FILTER_DENY = Pattern.compile(
            "(--|/\\*|\\*/|;|\\b(drop|delete|truncate|alter|create|grant|revoke|insert|update|union|exec|execute|select|with)\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final List<String> ALLOWED_AGG =
            List.of("SUM", "COUNT", "AVG", "MAX", "MIN", "COUNT_DISTINCT");
    private static final List<String> ALLOWED_GRAIN =
            List.of("day", "week", "month", "quarter", "year");

    private final AccessPolicyCompiler accessPolicyCompiler;

    /**
     * Pure function: model DTO + request + user → SQL + params.
     *
     * <p>The caller is responsible for loading the model DTO (e.g. by re-parsing
     * the persisted {@code yaml_source}) and supplying the request and user
     * context. The compiler performs no DB I/O.
     */
    public CompiledQuery compile(SemanticModelDTO model,
                                  SemanticQueryRequest req,
                                  UserContext user) {
        if (model == null || model.getSemanticModel() == null
                || model.getSemanticModel().getModelRef() == null) {
            throw new MetricCompileException("MODEL_REF_MISSING",
                    "SemanticModelDTO.semantic_model.model_ref required for compilation");
        }
        String table = model.getSemanticModel().getModelRef();
        assertIdent(table, "model_ref");

        // Resolve catalogue lookups up-front; fail fast.
        Map<String, MetricDTO> metricMap = byCode(model.getMetrics(), MetricDTO::getCode);
        Map<String, DimensionDTO> dimMap = byCode(model.getDimensions(), DimensionDTO::getCode);
        Map<String, MeasureDTO> measureMap = byCode(model.getMeasures(), MeasureDTO::getCode);

        List<MetricDTO> selectedMetrics = new ArrayList<>();
        for (String code : nullToEmpty(req.getMetrics())) {
            String bare = stripModelPrefix(code, model.getSemanticModel().getCode());
            MetricDTO m = metricMap.get(bare);
            if (m == null) {
                throw new MetricCompileException("UNKNOWN_METRIC",
                        "metric not found in model " + model.getSemanticModel().getCode() + ": " + code);
            }
            selectedMetrics.add(m);
        }

        // Dimensions: parse optional __grain suffix
        List<ResolvedDim> resolvedDims = new ArrayList<>();
        for (String raw : nullToEmpty(req.getDimensions())) {
            ResolvedDim rd = resolveDim(raw, dimMap, model.getSemanticModel().getCode());
            resolvedDims.add(rd);
        }

        boolean hasCumulative = selectedMetrics.stream()
                .anyMatch(m -> MetricType.CUMULATIVE.yamlValue().equalsIgnoreCase(m.getType()));
        if (hasCumulative) {
            // We need primary_time; the v0.1 cumulative path adds it to the grouping
            // automatically when not requested.
            DimensionDTO pt = findPrimaryTime(model);
            if (pt == null) {
                throw new MetricCompileException("NO_PRIMARY_TIME",
                        "cumulative metric requested but model has no primary_time dimension");
            }
            boolean already = resolvedDims.stream().anyMatch(d -> d.dim.getCode().equals(pt.getCode()));
            if (!already) {
                resolvedDims.add(new ResolvedDim(pt, null));
            }
        }

        // Conversion metrics take the dedicated cohort path (v0.2): the flat
        // single-FROM shape cannot express the entity self-join + window.
        List<MetricDTO> conversionMetrics = selectedMetrics.stream()
                .filter(m -> MetricType.CONVERSION.yamlValue().equalsIgnoreCase(m.getType()))
                .toList();
        if (!conversionMetrics.isEmpty()) {
            if (selectedMetrics.size() != 1) {
                throw new MetricCompileException("UNSUPPORTED_METRIC_COMBINATION",
                        "conversion metrics use a cohort self-join and must be queried alone:"
                                + " no mixing with other metric types, one conversion metric per request");
            }
            return compileCohortQuery(model, req, user, conversionMetrics.get(0), resolvedDims, measureMap, dimMap);
        }

        // SELECT columns + GROUP BY
        StringBuilder select = new StringBuilder("SELECT ");
        List<Object> params = new ArrayList<>();
        List<String> groupByOrdinals = new ArrayList<>();
        LinkedHashSet<String> referencedColumns = new LinkedHashSet<>();

        for (int i = 0; i < resolvedDims.size(); i++) {
            ResolvedDim rd = resolvedDims.get(i);
            assertIdent(rd.dim.getFieldRef(), "dimension.field_ref");
            referencedColumns.add(rd.dim.getFieldRef());
            String expr = rd.grain == null
                    ? rd.dim.getFieldRef()
                    : "DATE_TRUNC('" + rd.grain + "', " + rd.dim.getFieldRef() + ")";
            String alias = '"' + model.getSemanticModel().getCode() + "."
                    + rd.dim.getCode()
                    + (rd.grain == null ? "" : "__" + rd.grain) + '"';
            if (i > 0) select.append(", ");
            select.append(expr).append(" AS ").append(alias);
            groupByOrdinals.add(String.valueOf(i + 1));
        }

        // Metric expressions
        for (int i = 0; i < selectedMetrics.size(); i++) {
            MetricDTO m = selectedMetrics.get(i);
            if (!resolvedDims.isEmpty() || i > 0) select.append(", ");
            String metricExpr = compileMetricExpr(m, measureMap, metricMap, model, referencedColumns);
            String alias = '"' + model.getSemanticModel().getCode() + "." + m.getCode() + '"';
            select.append(metricExpr).append(" AS ").append(alias);
        }

        StringBuilder sql = new StringBuilder();
        sql.append(select).append("\nFROM ").append(table);

        // WHERE: tenant_id + time range + filters + RLS
        StringBuilder where = new StringBuilder("tenant_id = ?");
        params.add(user.tenantId());
        referencedColumns.add("tenant_id");

        if (req.getTimeRange() != null) {
            DimensionDTO tDim = dimMap.get(req.getTimeRange().getField());
            if (tDim == null) {
                throw new MetricCompileException("UNKNOWN_DIMENSION",
                        "timeRange.field not in model dims: " + req.getTimeRange().getField());
            }
            List<LocalDate> range = TimeRangeResolver.resolve(req.getTimeRange());
            if (!range.isEmpty()) {
                assertIdent(tDim.getFieldRef(), "timeRange.field_ref");
                where.append(" AND ").append(tDim.getFieldRef()).append(" BETWEEN ? AND ?");
                params.add(range.get(0));
                params.add(range.get(1));
                referencedColumns.add(tDim.getFieldRef());
            }
        }

        for (SemanticQueryRequest.Filter f : nullToEmpty(req.getFilters())) {
            DimensionDTO d = dimMap.get(f.getField());
            if (d == null) {
                throw new MetricCompileException("UNKNOWN_DIMENSION",
                        "filter.field not in model dims: " + f.getField());
            }
            assertIdent(d.getFieldRef(), "filter.field_ref");
            referencedColumns.add(d.getFieldRef());
            appendFilter(where, d.getFieldRef(), f, params);
        }

        // RLS — request-touched dims
        List<String> requestedDimCodes = new ArrayList<>();
        for (ResolvedDim rd : resolvedDims) requestedDimCodes.add(rd.dim.getCode());
        for (SemanticQueryRequest.Filter f : nullToEmpty(req.getFilters())) requestedDimCodes.add(f.getField());

        List<Object> rlsParams = accessPolicyCompiler.injectRls(
                where, model.getAccessPolicies(), requestedDimCodes, user);
        params.addAll(rlsParams);

        sql.append("\nWHERE ").append(where);

        if (!groupByOrdinals.isEmpty()) {
            sql.append("\nGROUP BY ").append(String.join(", ", groupByOrdinals));
        }

        // ORDER BY (by alias, validated against selected metrics + dims)
        if (req.getOrder() != null && !req.getOrder().isEmpty()) {
            sql.append("\nORDER BY ");
            for (int i = 0; i < req.getOrder().size(); i++) {
                SemanticQueryRequest.OrderBy o = req.getOrder().get(i);
                String dir = "desc".equalsIgnoreCase(o.getDir()) ? "DESC" : "ASC";
                String alias = '"' + model.getSemanticModel().getCode() + "." + o.getField() + '"';
                // Validate field references a selected metric or dim
                boolean known = selectedMetrics.stream().anyMatch(mm -> mm.getCode().equals(o.getField()))
                        || resolvedDims.stream().anyMatch(rd -> rd.dim.getCode().equals(o.getField()));
                if (!known) {
                    throw new MetricCompileException("UNKNOWN_METRIC",
                            "order field not in select list: " + o.getField());
                }
                if (i > 0) sql.append(", ");
                sql.append(alias).append(' ').append(dir);
            }
        }

        if (req.getLimit() > 0) {
            sql.append("\nLIMIT ?");
            params.add(req.getLimit());
        }
        if (req.getOffset() > 0) {
            sql.append(" OFFSET ?");
            params.add(req.getOffset());
        }

        String finalSql = sql.toString();
        return new CompiledQuery(finalSql, params, referencedColumns, fingerprint(finalSql));
    }

    // ---- metric expression dispatch -----------------------------------------

    private String compileMetricExpr(MetricDTO m,
                                      Map<String, MeasureDTO> measures,
                                      Map<String, MetricDTO> metrics,
                                      SemanticModelDTO model,
                                      LinkedHashSet<String> referencedColumns) {
        MetricType type;
        try {
            type = MetricType.fromYaml(m.getType());
        } catch (RuntimeException e) {
            throw new MetricCompileException("UNSUPPORTED_METRIC_TYPE",
                    "unknown metric.type: " + m.getType() + " for metric " + m.getCode());
        }
        Map<String, Object> p = m.getTypeParams() == null ? Map.of() : m.getTypeParams();
        switch (type) {
            case SIMPLE:
                return simpleExpr(m, measures, p, referencedColumns);
            case RATIO:
                return ratioExpr(m, measures, p, referencedColumns);
            case CUMULATIVE:
                return cumulativeExpr(m, measures, p, model, referencedColumns);
            case DERIVED:
                return derivedExpr(m, measures, metrics, model, p, referencedColumns);
            case CONVERSION:
                throw new MetricCompileException("UNSUPPORTED_METRIC_COMBINATION",
                        "conversion metric " + m.getCode()
                                + " compiles via the cohort self-join path and cannot be referenced"
                                + " from derived metrics; query it directly");
            default:
                throw new MetricCompileException("UNSUPPORTED_METRIC_TYPE",
                        "metric type " + type + " not implemented in v0.1");
        }
    }

    private String simpleExpr(MetricDTO m, Map<String, MeasureDTO> measures,
                               Map<String, Object> p, LinkedHashSet<String> referencedColumns) {
        String measureCode = (String) p.get("measure");
        MeasureDTO measure = requireMeasure(measureCode, measures, m.getCode());
        String aggExpr = aggExpr(measure, referencedColumns);
        if (m.getFilter() != null && !m.getFilter().isBlank()) {
            // FILTER (WHERE ...) — the filter expression is part of the YAML metric
            // and already validated by SemanticValidator (no user values, no injection
            // tokens). It's inlined as-is.
            return aggExpr + " FILTER (WHERE " + m.getFilter() + ")";
        }
        return aggExpr;
    }

    private String ratioExpr(MetricDTO m, Map<String, MeasureDTO> measures,
                              Map<String, Object> p, LinkedHashSet<String> referencedColumns) {
        MeasureDTO num = requireMeasure((String) p.get("numerator"), measures, m.getCode());
        MeasureDTO den = requireMeasure((String) p.get("denominator"), measures, m.getCode());
        String numExpr = aggExpr(num, referencedColumns);
        String denExpr = aggExpr(den, referencedColumns);
        return numExpr + "::numeric / NULLIF(" + denExpr + ", 0)";
    }

    private String cumulativeExpr(MetricDTO m, Map<String, MeasureDTO> measures,
                                   Map<String, Object> p, SemanticModelDTO model,
                                   LinkedHashSet<String> referencedColumns) {
        String measureCode = (String) p.get("measure");
        String window = String.valueOf(p.getOrDefault("window", "running")).toLowerCase(Locale.ROOT);
        MeasureDTO measure = requireMeasure(measureCode, measures, m.getCode());
        DimensionDTO pt = findPrimaryTime(model);
        if (pt == null) {
            throw new MetricCompileException("NO_PRIMARY_TIME",
                    "cumulative metric " + m.getCode() + " requires primary_time dim");
        }
        assertIdent(pt.getFieldRef(), "primary_time.field_ref");
        referencedColumns.add(pt.getFieldRef());
        String baseAgg = aggExpr(measure, referencedColumns);

        String partition;
        switch (window) {
            case "ytd":
                partition = "PARTITION BY EXTRACT(YEAR FROM " + pt.getFieldRef() + ")";
                break;
            case "mtd":
                partition = "PARTITION BY EXTRACT(YEAR FROM " + pt.getFieldRef() + "), "
                        + "EXTRACT(MONTH FROM " + pt.getFieldRef() + ")";
                break;
            case "qtd":
                partition = "PARTITION BY EXTRACT(YEAR FROM " + pt.getFieldRef() + "), "
                        + "EXTRACT(QUARTER FROM " + pt.getFieldRef() + ")";
                break;
            case "running":
                partition = "";
                break;
            default:
                throw new MetricCompileException("TIMERANGE_INVALID",
                        "unknown cumulative window: " + window);
        }
        return "SUM(" + baseAgg + ") OVER (" + partition
                + (partition.isEmpty() ? "" : " ")
                + "ORDER BY " + pt.getFieldRef() + ")";
    }

    private String derivedExpr(MetricDTO m, Map<String, MeasureDTO> measures,
                                Map<String, MetricDTO> metrics, SemanticModelDTO model,
                                Map<String, Object> p, LinkedHashSet<String> referencedColumns) {
        String expr = (String) p.get("expr");
        if (expr == null) {
            throw new MetricCompileException("DERIVED_PLACEHOLDER_UNRESOLVED",
                    "derived metric " + m.getCode() + " missing type_params.expr");
        }
        Matcher mt = Pattern.compile("\\{([a-z][a-z0-9_]*)\\}").matcher(expr);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (mt.find()) {
            out.append(expr, last, mt.start());
            String ref = mt.group(1);
            MetricDTO referencedMetric = metrics.get(ref);
            MeasureDTO referencedMeasure = measures.get(ref);
            if (referencedMetric == null && referencedMeasure == null) {
                throw new MetricCompileException("DERIVED_PLACEHOLDER_UNRESOLVED",
                        "derived metric " + m.getCode()
                                + " references unknown metric/measure: " + ref);
            }
            String inner;
            if (referencedMetric != null) {
                // Prevent obvious cycles for v0.1 (only allow non-derived references).
                // TODO v0.2: full topological resolution.
                if (MetricType.DERIVED.yamlValue().equalsIgnoreCase(referencedMetric.getType())) {
                    throw new MetricCompileException("DERIVED_PLACEHOLDER_UNRESOLVED",
                            "derived-of-derived not supported in v0.1: " + ref);
                }
                inner = compileMetricExpr(referencedMetric, measures, metrics, model, referencedColumns);
            } else {
                inner = aggExpr(referencedMeasure, referencedColumns);
            }
            out.append("(").append(inner).append(")");
            last = mt.end();
        }
        out.append(expr, last, expr.length());
        return out.toString();
    }

    /**
     * v0.2 cohort conversion: replaces the v0.1 simplified ratio, which ignored
     * the declared window entirely.
     *
     * <p>Fixed semantics (asserted in {@code MetricCompilerTest} and the
     * semantic-layer integration tests):
     * <ul>
     *   <li>Cohort entry = first base event (MIN(primary_time)) per
     *       (entity, dims); later base events (reopens) never multiply a cohort.</li>
     *   <li>Conversion = EXISTS a conversion event for the same entity whose
     *       event time falls in {@code [entry, entry + window)}; duplicate
     *       conversion events count once.</li>
     *   <li>Denominator = mature cohorts only: {@code entry + window <= as-of},
     *       where as-of is the request time-range end or CURRENT_DATE. Immature
     *       cohorts stay out of the denominator ("observing"), never counted
     *       as failures. Conversions after the window are ignored.</li>
     * </ul>
     *
     * <p>{@code base_filter} / {@code conversion_filter} are YAML-author SQL
     * fragments selecting base vs conversion event rows (publish-time checked
     * by SemanticYamlValidator, re-checked here — they are interpolated into a
     * subquery WHERE, so {@code SELECT}/{@code WITH} etc. must never compile).
     * Request filters, time range and RLS constrain the base (entry) side; the
     * conversion side is constrained by tenant, entity equality, its filter,
     * RLS and the window bounds.
     */
    private CompiledQuery compileCohortQuery(SemanticModelDTO model,
                                              SemanticQueryRequest req,
                                              UserContext user,
                                              MetricDTO metric,
                                              List<ResolvedDim> resolvedDims,
                                              Map<String, MeasureDTO> measureMap,
                                              Map<String, DimensionDTO> dimMap) {
        String table = model.getSemanticModel().getModelRef();
        String modelCode = model.getSemanticModel().getCode();
        DimensionDTO pt = findPrimaryTime(model);
        if (pt == null) {
            throw new MetricCompileException("NO_PRIMARY_TIME",
                    "conversion metric requires a primary_time dimension for cohort entry");
        }
        String ptRef = pt.getFieldRef();
        assertIdent(ptRef, "primary_time.field_ref");

        LinkedHashSet<String> referencedColumns = new LinkedHashSet<>();
        referencedColumns.add("tenant_id");
        referencedColumns.add(ptRef);
        for (ResolvedDim rd : resolvedDims) {
            assertIdent(rd.dim.getFieldRef(), "dimension.field_ref");
            referencedColumns.add(rd.dim.getFieldRef());
        }
        CohortSpec spec = cohortSpec(metric, model, measureMap, referencedColumns);
        // Ambiguity guard: a model column EXACTLY named like an internal cohort
        // alias would make the outer references ambiguous. Prefix matches are
        // harmless — platform field codes routinely carry arbitrary product
        // prefixes (e.g. amos_cf_*), so only the exact names are rejected.
        for (String col : referencedColumns) {
            if (COHORT_ENTITY_ALIAS.equals(col) || COHORT_ENTRY_TIME_ALIAS.equals(col)
                    || COHORT_CONV_ALIAS.equals(col)) {
                throw new MetricCompileException("UNKNOWN_DIMENSION",
                        "column '" + col + "' collides with an internal cohort alias name");
            }
        }

        // SELECT: dims (same shape as the flat path — the subquery carries raw
        // columns under their field_ref names) + the cohort rate.
        StringBuilder select = new StringBuilder("SELECT ");
        List<String> groupByOrdinals = new ArrayList<>();
        for (int i = 0; i < resolvedDims.size(); i++) {
            ResolvedDim rd = resolvedDims.get(i);
            String expr = rd.grain == null
                    ? rd.dim.getFieldRef()
                    : "DATE_TRUNC('" + rd.grain + "', " + rd.dim.getFieldRef() + ")";
            String alias = '"' + modelCode + "." + rd.dim.getCode()
                    + (rd.grain == null ? "" : "__" + rd.grain) + '"';
            if (i > 0) select.append(", ");
            select.append(expr).append(" AS ").append(alias);
            groupByOrdinals.add(String.valueOf(i + 1));
        }
        if (!resolvedDims.isEmpty()) select.append(", ");
        select.append("COUNT(DISTINCT CASE WHEN t.amos_cv THEN t.amos_ce END)::numeric")
                .append(" / NULLIF(COUNT(DISTINCT t.amos_ce), 0)::numeric AS \"")
                .append(modelCode).append('.').append(metric.getCode()).append('"');

        List<String> requestedDimCodes = new ArrayList<>();
        for (ResolvedDim rd : resolvedDims) requestedDimCodes.add(rd.dim.getCode());
        for (SemanticQueryRequest.Filter f : nullToEmpty(req.getFilters())) requestedDimCodes.add(f.getField());

        // Params must follow textual order: EXISTS → maturity → base side → tail.
        List<Object> params = new ArrayList<>();
        List<Object> tailParams = new ArrayList<>();

        StringBuilder existsSql = new StringBuilder();
        existsSql.append("EXISTS (\n             SELECT 1 FROM ").append(table)
                .append(" cv\n             WHERE cv.tenant_id = ?");
        params.add(user.tenantId());
        existsSql.append("\n               AND cv.").append(spec.entityRef())
                .append(" = b.").append(spec.entityRef())
                .append("\n               AND (").append(spec.convFilter()).append(')')
                .append("\n               AND cv.").append(ptRef).append(" >= b.amos_et")
                .append("\n               AND cv.").append(ptRef)
                .append(" < b.amos_et + (?::bigint * INTERVAL '1 second')");
        params.add(spec.windowSeconds());
        StringBuilder convRls = new StringBuilder();
        params.addAll(accessPolicyCompiler.injectRls(convRls, model.getAccessPolicies(), requestedDimCodes, user));
        existsSql.append(convRls).append("\n           )");

        List<Object> matureParams = new ArrayList<>();
        StringBuilder matureSql = new StringBuilder("WHERE b.amos_et + (?::bigint * INTERVAL '1 second') <= ");
        matureParams.add(spec.windowSeconds());
        List<LocalDate> range = List.of();
        if (req.getTimeRange() != null) {
            DimensionDTO tDim = dimMap.get(req.getTimeRange().getField());
            if (tDim == null) {
                throw new MetricCompileException("UNKNOWN_DIMENSION",
                        "timeRange.field not in model dims: " + req.getTimeRange().getField());
            }
            range = TimeRangeResolver.resolve(req.getTimeRange());
        }
        if (!range.isEmpty()) {
            matureSql.append("?::date");
            matureParams.add(range.get(1));
        } else {
            matureSql.append("CURRENT_DATE");
        }

        List<Object> baseParams = new ArrayList<>();
        StringBuilder baseWhere = new StringBuilder("tenant_id = ? AND (")
                .append(spec.baseFilter()).append(')');
        baseParams.add(user.tenantId());
        if (!range.isEmpty()) {
            baseWhere.append(" AND ").append(ptRef).append(" BETWEEN ?::date AND ?::date");
            baseParams.add(range.get(0));
            baseParams.add(range.get(1));
        }
        for (SemanticQueryRequest.Filter f : nullToEmpty(req.getFilters())) {
            DimensionDTO d = dimMap.get(f.getField());
            if (d == null) {
                throw new MetricCompileException("UNKNOWN_DIMENSION",
                        "filter.field not in model dims: " + f.getField());
            }
            assertIdent(d.getFieldRef(), "filter.field_ref");
            appendFilter(baseWhere, d.getFieldRef(), f, baseParams);
        }
        StringBuilder baseRls = new StringBuilder();
        baseParams.addAll(accessPolicyCompiler.injectRls(baseRls, model.getAccessPolicies(), requestedDimCodes, user));

        StringBuilder sql = new StringBuilder();
        sql.append(select)
                .append("\nFROM (\n  SELECT b.").append(spec.entityRef()).append(" AS amos_ce")
                .append(",\n         b.amos_et AS amos_et");
        for (ResolvedDim rd : resolvedDims) {
            sql.append(",\n         b.").append(rd.dim.getFieldRef());
        }
        sql.append(",\n         ").append(existsSql).append(" AS amos_cv");
        sql.append("\n  FROM (\n    SELECT ").append(spec.entityRef())
                .append(", MIN(").append(ptRef).append(") AS amos_et");
        for (ResolvedDim rd : resolvedDims) {
            sql.append(", ").append(rd.dim.getFieldRef());
        }
        sql.append("\n    FROM ").append(table)
                .append("\n    WHERE ").append(baseWhere).append(baseRls)
                .append("\n    GROUP BY ").append(spec.entityRef());
        for (ResolvedDim rd : resolvedDims) {
            sql.append(", ").append(rd.dim.getFieldRef());
        }
        sql.append("\n  ) b\n  ").append(matureSql).append("\n) t");

        if (!groupByOrdinals.isEmpty()) {
            sql.append("\nGROUP BY ").append(String.join(", ", groupByOrdinals));
        }

        // ORDER BY (by alias, validated against the selected metric + dims)
        if (req.getOrder() != null && !req.getOrder().isEmpty()) {
            sql.append("\nORDER BY ");
            for (int i = 0; i < req.getOrder().size(); i++) {
                SemanticQueryRequest.OrderBy o = req.getOrder().get(i);
                String dir = "desc".equalsIgnoreCase(o.getDir()) ? "DESC" : "ASC";
                String alias = '"' + modelCode + "." + o.getField() + '"';
                boolean known = metric.getCode().equals(o.getField())
                        || resolvedDims.stream().anyMatch(rd -> rd.dim.getCode().equals(o.getField()));
                if (!known) {
                    throw new MetricCompileException("UNKNOWN_METRIC",
                            "order field not in select list: " + o.getField());
                }
                if (i > 0) sql.append(", ");
                sql.append(alias).append(' ').append(dir);
            }
        }
        if (req.getLimit() > 0) {
            sql.append("\nLIMIT ?");
            tailParams.add(req.getLimit());
        }
        if (req.getOffset() > 0) {
            sql.append(" OFFSET ?");
            tailParams.add(req.getOffset());
        }

        // Textual order: EXISTS → base (entry) side → maturity → tail. The base
        // subquery appears in FROM, before the maturity WHERE, so its params must
        // bind first — an order slip here silently mismatches every parameter.
        params.addAll(baseParams);
        params.addAll(matureParams);
        params.addAll(tailParams);
        String finalSql = sql.toString();
        return new CompiledQuery(finalSql, params, referencedColumns, fingerprint(finalSql));
    }

    /** Resolves and validates the conversion metric's cohort parameters. */
    private CohortSpec cohortSpec(MetricDTO m, SemanticModelDTO model,
                                   Map<String, MeasureDTO> measures,
                                   LinkedHashSet<String> referencedColumns) {
        Map<String, Object> p = m.getTypeParams() == null ? Map.of() : m.getTypeParams();
        // The measures stay part of the declared contract (what counts as
        // base/conversion); the SQL selects event rows via the filters below.
        requireMeasure((String) p.get("base_measure"), measures, m.getCode());
        requireMeasure((String) p.get("conversion_measure"), measures, m.getCode());
        String entityName = String.valueOf(p.get("entity"));
        String entityRef = nullToEmpty(model.getEntities()).stream()
                .filter(e -> entityName.equals(e.getName()))
                .map(EntityDTO::getFieldRef)
                .findFirst()
                .orElseThrow(() -> new MetricCompileException("UNKNOWN_DIMENSION",
                        "metric " + m.getCode() + " conversion.entity '" + entityName
                                + "' not declared in entities[]"));
        if (entityRef == null || !IDENT.matcher(entityRef).matches()) {
            throw new MetricCompileException("UNKNOWN_DIMENSION",
                    "metric " + m.getCode() + " conversion entity field_ref invalid: " + entityRef);
        }
        referencedColumns.add(entityRef);
        String baseFilter = requireCohortFilter(p, "base_filter", m.getCode());
        String convFilter = requireCohortFilter(p, "conversion_filter", m.getCode());
        long seconds = windowSeconds(String.valueOf(p.get("window")), m.getCode());
        return new CohortSpec(m.getCode(), entityRef, baseFilter, convFilter, seconds);
    }

    private String requireCohortFilter(Map<String, Object> p, String key, String metricCode) {
        Object v = p.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new MetricCompileException("COHORT_FILTER_MISSING",
                    "metric " + metricCode + " type_params." + key
                            + " is required: the cohort self-join selects event rows via explicit filters");
        }
        String f = String.valueOf(v);
        if (COHORT_FILTER_DENY.matcher(f).find()) {
            throw new MetricCompileException("SQL_INJECTION_DETECTED",
                    "metric " + metricCode + " type_params." + key + " contains denylisted token");
        }
        return f;
    }

    /**
     * {@code window: \d+[dhwmy]} → seconds. {@code m} is a fixed 30-day month
     * and {@code y} a fixed 365-day year (documented approximation; AMOS
     * observation windows are declared in days).
     */
    private long windowSeconds(String raw, String metricCode) {
        Matcher m = Pattern.compile("^(\\d+)([dhwmy])$").matcher(raw == null ? "" : raw);
        if (!m.matches()) {
            throw new MetricCompileException("TIMERANGE_INVALID",
                    "metric " + metricCode + " conversion.window must match \\d+[dhwmy] (was: " + raw + ")");
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "h" -> n * 3600L;
            case "d" -> n * 86400L;
            case "w" -> n * 604800L;
            case "m" -> n * 2592000L;
            case "y" -> n * 31536000L;
            default -> throw new MetricCompileException("TIMERANGE_INVALID",
                    "metric " + metricCode + " unknown window unit: " + m.group(2));
        };
    }

    private record CohortSpec(String code, String entityRef, String baseFilter,
                              String convFilter, long windowSeconds) {}

    // ---- helpers ------------------------------------------------------------

    private String aggExpr(MeasureDTO measure, LinkedHashSet<String> referencedColumns) {
        String agg = measure.getAgg() == null ? "" : measure.getAgg().toUpperCase(Locale.ROOT);
        if (!ALLOWED_AGG.contains(agg)) {
            throw new MetricCompileException("UNSUPPORTED_AGGREGATION",
                    "measure.agg must be one of " + ALLOWED_AGG + " (was: " + measure.getAgg() + ")");
        }
        String inner;
        if (measure.getExpr() != null && !measure.getExpr().isBlank()) {
            // YAML-author SQL fragment (validated by SemanticValidator).
            inner = measure.getExpr();
        } else if (measure.getFieldRef() != null) {
            assertIdent(measure.getFieldRef(), "measure.field_ref");
            inner = measure.getFieldRef();
            referencedColumns.add(measure.getFieldRef());
        } else {
            throw new MetricCompileException("UNKNOWN_MEASURE",
                    "measure " + measure.getCode() + " missing field_ref and expr");
        }
        if ("COUNT_DISTINCT".equals(agg)) {
            return "COUNT(DISTINCT " + inner + ")";
        }
        return agg + "(" + inner + ")";
    }

    private MeasureDTO requireMeasure(String code, Map<String, MeasureDTO> measures, String metricCode) {
        if (code == null) {
            throw new MetricCompileException("UNKNOWN_MEASURE",
                    "metric " + metricCode + " type_params missing measure code");
        }
        MeasureDTO d = measures.get(code);
        if (d == null) {
            throw new MetricCompileException("UNKNOWN_MEASURE",
                    "metric " + metricCode + " references unknown measure: " + code);
        }
        return d;
    }

    private DimensionDTO findPrimaryTime(SemanticModelDTO model) {
        for (DimensionDTO d : nullToEmpty(model.getDimensions())) {
            if (Boolean.TRUE.equals(d.getPrimaryTime())) {
                return d;
            }
        }
        return null;
    }

    private ResolvedDim resolveDim(String raw, Map<String, DimensionDTO> dimMap, String modelCode) {
        String bare = stripModelPrefix(raw, modelCode);
        String code = bare;
        String grain = null;
        int sep = bare.indexOf("__");
        if (sep > 0) {
            code = bare.substring(0, sep);
            grain = bare.substring(sep + 2).toLowerCase(Locale.ROOT);
            if (!ALLOWED_GRAIN.contains(grain)) {
                throw new MetricCompileException("TIMERANGE_INVALID",
                        "unsupported time grain: " + grain);
            }
        }
        DimensionDTO d = dimMap.get(code);
        if (d == null) {
            throw new MetricCompileException("UNKNOWN_DIMENSION",
                    "dimension not found in model: " + raw);
        }
        if (grain != null && !"time".equalsIgnoreCase(d.getType())) {
            throw new MetricCompileException("UNKNOWN_DIMENSION",
                    "grain suffix only valid on time dimensions: " + raw);
        }
        return new ResolvedDim(d, grain);
    }

    private void appendFilter(StringBuilder where, String column,
                               SemanticQueryRequest.Filter f, List<Object> params) {
        String op = f.getOp() == null ? "eq" : f.getOp().toLowerCase(Locale.ROOT);
        switch (op) {
            case "eq":
                where.append(" AND ").append(column).append(" = ?");
                params.add(f.getValue());
                return;
            case "ne":
                where.append(" AND ").append(column).append(" <> ?");
                params.add(f.getValue());
                return;
            case "gt":
                where.append(" AND ").append(column).append(" > ?");
                params.add(f.getValue());
                return;
            case "gte":
                where.append(" AND ").append(column).append(" >= ?");
                params.add(f.getValue());
                return;
            case "lt":
                where.append(" AND ").append(column).append(" < ?");
                params.add(f.getValue());
                return;
            case "lte":
                where.append(" AND ").append(column).append(" <= ?");
                params.add(f.getValue());
                return;
            case "like":
                where.append(" AND ").append(column).append(" LIKE ?");
                params.add(f.getValue());
                return;
            case "in":
            case "not_in": {
                if (!(f.getValue() instanceof List<?> list) || list.isEmpty()) {
                    throw new MetricCompileException("UNSUPPORTED_AGGREGATION",
                            "filter op=" + op + " requires a non-empty list value");
                }
                where.append(" AND ").append(column)
                        .append("not_in".equals(op) ? " NOT IN (" : " IN (");
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) where.append(", ");
                    where.append("?");
                    params.add(list.get(i));
                }
                where.append(")");
                return;
            }
            default:
                throw new MetricCompileException("UNSUPPORTED_AGGREGATION",
                        "unknown filter op: " + f.getOp());
        }
    }

    /** Strips {@code "<modelCode>."} prefix if present. */
    private String stripModelPrefix(String code, String modelCode) {
        if (code == null) return null;
        String prefix = modelCode + ".";
        return code.startsWith(prefix) ? code.substring(prefix.length()) : code;
    }

    private static void assertIdent(String s, String what) {
        if (s == null || !IDENT.matcher(s).matches()) {
            throw new MetricCompileException("UNKNOWN_DIMENSION",
                    what + " is not a valid SQL identifier: " + s);
        }
    }

    private static <T> Map<String, T> byCode(List<T> items, java.util.function.Function<T, String> keyFn) {
        Map<String, T> m = new LinkedHashMap<>();
        for (T t : nullToEmpty(items)) {
            String k = keyFn.apply(t);
            if (k != null) m.put(k, t);
        }
        return m;
    }

    private static <T> List<T> nullToEmpty(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static String fingerprint(String sql) {
        try {
            String norm = sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(norm.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Internal: a dimension + its grain qualifier (null = no qualifier). */
    private record ResolvedDim(DimensionDTO dim, String grain) {}
}
