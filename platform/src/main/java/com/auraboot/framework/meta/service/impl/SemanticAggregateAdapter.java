package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.dto.AggregateQueryResponse;
import com.auraboot.framework.meta.dto.MetricConfig;
import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.semantic.dto.SemanticQueryResponse;
import com.auraboot.framework.semantic.service.SemanticQueryService;
import com.auraboot.framework.userattribute.service.UserAttributeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes an {@link AggregateQueryRequest} that carries a
 * {@code semanticModelCode} through the semantic layer
 * ({@link SemanticQueryService}) instead of the raw model + SQL builder path.
 *
 * <p>PRD 16 §6 W4 D4 integration. Dashboard charts (and any other
 * AggregateQueryService consumer) opt into governed-metric semantics by
 * setting {@link AggregateQueryRequest#getSemanticModelCode()} on the
 * request; everything else stays bit-identical to the legacy path so
 * existing widgets do not regress.
 *
 * <p>The adapter is intentionally a thin translator:
 * <ul>
 *   <li>{@code metrics[].alias != null ? alias : field} → semantic metric code,
 *       qualified with {@code <semanticModelCode>.} when the caller passed an
 *       unqualified code.</li>
 *   <li>{@code dimensions[]} → semantic dimensions verbatim (grain suffix
 *       {@code __month} etc. is honoured by the semantic compiler).</li>
 *   <li>{@code filters[]} → semantic Filter list with operator lowercased
 *       (semantic compiler accepts {@code eq, ne, gt, gte, lt, lte, in, not_in, like}).</li>
 *   <li>{@code drillFilters[]} appended onto the same Filter list (no special
 *       semantics at this layer; the compiler treats them as AND).</li>
 *   <li>{@code limit} / {@code orderBy} forwarded.</li>
 * </ul>
 *
 * <p>Bean is wired with {@link ObjectProvider} so the legacy
 * {@code AggregateQueryServiceImpl} does not require the semantic stack to
 * boot — environments running only the dynamic-aggregate path stay green.
 */
@Slf4j
@Component
public class SemanticAggregateAdapter {

    private final ObjectProvider<SemanticQueryService> semanticQueryProvider;
    private final ObjectProvider<UserAttributeService> attributeProvider;

    public SemanticAggregateAdapter(ObjectProvider<SemanticQueryService> semanticQueryProvider,
                                    ObjectProvider<UserAttributeService> attributeProvider) {
        this.semanticQueryProvider = semanticQueryProvider;
        this.attributeProvider = attributeProvider;
    }

    /**
     * Execute via the semantic layer.
     *
     * @throws IllegalStateException when the semantic stack is not available
     */
    public AggregateQueryResponse execute(AggregateQueryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        String modelCode = request.getSemanticModelCode();
        if (modelCode == null || modelCode.isBlank()) {
            throw new IllegalArgumentException(
                    "semanticModelCode required for semantic-routed execution");
        }
        SemanticQueryService service = semanticQueryProvider.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException(
                    "SemanticQueryService not on the classpath — semantic-routed query rejected");
        }

        SemanticQueryRequest semReq = translate(request, modelCode);
        UserContext ctx = buildUserContext();
        SemanticQueryResponse semResp = service.executeQuery(semReq, ctx);

        return rebuild(semResp, request);
    }

    // -- request translation --------------------------------------------

    SemanticQueryRequest translate(AggregateQueryRequest request, String modelCode) {
        SemanticQueryRequest sem = new SemanticQueryRequest();
        if (request.getMetrics() != null) {
            for (MetricConfig m : request.getMetrics()) {
                String code = pickMetricCode(m);
                if (code == null || code.isBlank()) continue;
                sem.getMetrics().add(qualify(code, modelCode));
            }
        }
        if (request.getDimensions() != null) {
            for (String d : request.getDimensions()) {
                if (d != null && !d.isBlank()) sem.getDimensions().add(d);
            }
        }
        if (request.getFilters() != null) {
            for (AggregateQueryRequest.FilterConfig f : request.getFilters()) {
                appendFilter(sem, f);
            }
        }
        if (request.getDrillFilters() != null) {
            for (AggregateQueryRequest.FilterConfig f : request.getDrillFilters()) {
                appendFilter(sem, f);
            }
        }
        if (request.getOrderBy() != null) {
            for (AggregateQueryRequest.OrderByConfig o : request.getOrderBy()) {
                if (o == null || o.getField() == null) continue;
                SemanticQueryRequest.OrderBy ob = new SemanticQueryRequest.OrderBy();
                ob.setField(o.getField());
                ob.setDir(o.getDirection() == null ? "asc"
                        : o.getDirection().toLowerCase(java.util.Locale.ROOT));
                sem.getOrder().add(ob);
            }
        }
        if (request.getLimit() != null) {
            sem.setLimit(request.getLimit());
        }
        return sem;
    }

    /**
     * Prefer the aliased name (chart-side rename) for semantic resolution; fall
     * back to the raw field code. The semantic compiler rejects codes that do not
     * match a declared metric in the model, so callers cannot smuggle arbitrary
     * column names through the semantic path.
     */
    private static String pickMetricCode(MetricConfig m) {
        if (m == null) return null;
        if (m.getAlias() != null && !m.getAlias().isBlank()) return m.getAlias();
        if (m.getField() != null && !m.getField().isBlank()) return m.getField();
        return null;
    }

    private static String qualify(String code, String modelCode) {
        if (code == null) return null;
        if (code.contains(".")) return code;
        return modelCode + "." + code;
    }

    private void appendFilter(SemanticQueryRequest sem,
                              AggregateQueryRequest.FilterConfig f) {
        if (f == null || f.getField() == null) return;
        SemanticQueryRequest.Filter sf = new SemanticQueryRequest.Filter();
        sf.setField(f.getField());
        sf.setOp(f.getOperator() == null ? "eq"
                : f.getOperator().toLowerCase(java.util.Locale.ROOT));
        sf.setValue(f.getValue());
        sem.getFilters().add(sf);
    }

    private UserContext buildUserContext() {
        Long tenantId = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        Long userId = MetaContext.exists() ? MetaContext.getCurrentUserId() : null;
        UserAttributeService attrs = attributeProvider.getIfAvailable();
        Map<String, String> attributeMap;
        if (attrs != null && tenantId != null && userId != null) {
            attributeMap = attrs.getAttributes(tenantId, userId);
        } else {
            attributeMap = Collections.emptyMap();
        }
        return new UserContext(userId, tenantId, attributeMap);
    }

    // -- response shape -------------------------------------------------

    AggregateQueryResponse rebuild(SemanticQueryResponse semResp,
                                   AggregateQueryRequest request) {
        AggregateQueryResponse out = new AggregateQueryResponse();
        out.setRows(semResp.getRows() == null ? List.of() : semResp.getRows());

        AggregateQueryResponse.QueryMeta meta = new AggregateQueryResponse.QueryMeta();
        meta.setDimensions(request.getDimensions());
        List<String> metricNames = new ArrayList<>();
        if (request.getMetrics() != null) {
            for (MetricConfig m : request.getMetrics()) {
                String code = pickMetricCode(m);
                if (code != null) metricNames.add(code);
            }
        }
        meta.setMetrics(metricNames);
        out.setMeta(meta);
        out.setSummary(emptyOrSummary(semResp));
        return out;
    }

    private static Map<String, Object> emptyOrSummary(SemanticQueryResponse semResp) {
        // The semantic layer does not produce a grand-total in v0.1; surface an
        // empty map so chart components can still call .summary.x without NPE.
        Map<String, Object> summary = new LinkedHashMap<>();
        if (semResp != null && semResp.getRowcount() != 0) {
            summary.put("rowcount", semResp.getRowcount());
        }
        return summary;
    }
}
