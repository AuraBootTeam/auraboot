package com.auraboot.framework.semantic.compiler;

import com.auraboot.framework.semantic.dto.AccessPolicyDTO;
import com.auraboot.framework.semantic.dto.DimensionDTO;
import com.auraboot.framework.semantic.dto.MeasureDTO;
import com.auraboot.framework.semantic.dto.MetricDTO;
import com.auraboot.framework.semantic.dto.SemanticModelDTO;
import com.auraboot.framework.semantic.parser.SemanticYamlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for MetricCompiler. Uses the real sales.semantic.yml fixture to
 * keep tests rooted in PRD's published surface (and to catch DTO drift).
 *
 * <p>No DB; the compiler is a pure function over the parsed DTO.
 */
class MetricCompilerTest {

    private SemanticModelDTO salesModel;
    private MetricCompiler compiler;
    private UserContext user;

    @BeforeEach
    void setup() throws IOException {
        SemanticYamlParser parser = new SemanticYamlParser();
        byte[] yaml;
        try (var in = new ClassPathResource("semantic/valid/sales.semantic.yml").getInputStream()) {
            yaml = in.readAllBytes();
        }
        salesModel = parser.parse(new String(yaml, StandardCharsets.UTF_8));
        compiler = new MetricCompiler(new AccessPolicyCompiler());
        user = new UserContext(42L, 7L, Map.of("allowed_regions", "CN,US"));
    }

    private SemanticQueryRequest req() {
        return new SemanticQueryRequest();
    }

    // ---- 1. simple metric + 1 dim --------------------------------------------

    @Test
    void simple_metric_with_one_dim() {
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("total_sales"));
        r.setDimensions(List.of("region"));
        CompiledQuery q = compiler.compile(salesModel, r, user);
        assertThat(q.getSql()).contains("SELECT region_code AS \"sales.region\"");
        assertThat(q.getSql()).contains("SUM(amount) FILTER (WHERE status != 'CANCELLED') AS \"sales.total_sales\"");
        assertThat(q.getSql()).contains("FROM ord_sales_order");
        assertThat(q.getSql()).contains("tenant_id = ?");
        assertThat(q.getSql()).contains("GROUP BY 1");
        assertThat(q.getParams()).startsWith(7L);
        assertThat(q.getSqlFingerprint()).hasSize(64);
        assertThat(q.getReferencedColumns()).contains("region_code", "amount", "tenant_id");
    }

    // ---- 2. simple + filter + multiple dims ----------------------------------

    @Test
    void simple_with_filter_and_multi_dims() {
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("total_sales"));
        r.setDimensions(List.of("region", "status"));
        r.getFilters().add(new SemanticQueryRequest.Filter("status", "in", List.of("PAID", "SHIPPED")));
        CompiledQuery q = compiler.compile(salesModel, r, user);
        assertThat(q.getSql()).contains("GROUP BY 1, 2");
        assertThat(q.getSql()).contains("status IN (?, ?)");
        assertThat(q.getParams()).containsSubsequence(7L, "PAID", "SHIPPED");
    }

    // ---- 3. ratio metric -----------------------------------------------------

    @Test
    void ratio_metric_emits_nullif() {
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("paid_conversion_rate"));
        r.setDimensions(List.of("region"));
        CompiledQuery q = compiler.compile(salesModel, r, user);
        assertThat(q.getSql()).contains("NULLIF(");
        assertThat(q.getSql()).contains("::numeric /");
        assertThat(q.getSql()).contains("\"sales.paid_conversion_rate\"");
    }

    // ---- 4. cumulative metric (ytd) ------------------------------------------

    @Test
    void cumulative_ytd_uses_window() {
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("ytd_sales"));
        CompiledQuery q = compiler.compile(salesModel, r, user);
        assertThat(q.getSql()).contains("OVER (PARTITION BY EXTRACT(YEAR FROM order_date) ORDER BY order_date)");
        // primary_time auto-added to grouping
        assertThat(q.getSql()).contains("order_date AS \"sales.order_date\"");
    }

    // ---- 5. derived metric ---------------------------------------------------

    @Test
    void derived_substitutes_metric_placeholders() {
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("avg_order_value"));
        r.setDimensions(List.of("region"));
        CompiledQuery q = compiler.compile(salesModel, r, user);
        // expr was "{total_sales} / {order_count}"
        assertThat(q.getSql()).contains("(SUM(amount) FILTER (WHERE status != 'CANCELLED'))");
        assertThat(q.getSql()).contains("(COUNT(*))");
        assertThat(q.getSql()).contains("/");
        assertThat(q.getSql()).contains("\"sales.avg_order_value\"");
    }

    // ---- 6. conversion metric (v0.2 cohort self-join) ------------------------

    private MetricDTO cohortConvMetric(Map<String, Object> overrides) {
        Map<String, Object> params = new java.util.HashMap<>(Map.of(
                "base_measure", "order_count",
                "conversion_measure", "paid_order_count",
                "entity", "customer_id",
                "window", "30d",
                "base_filter", "status = 'trial'",
                "conversion_filter", "status = 'paid'"));
        params.putAll(overrides);
        MetricDTO conv = new MetricDTO();
        conv.setCode("paid_conv");
        conv.setType("conversion");
        conv.setTypeParams(params);
        salesModel.getMetrics().add(conv);
        return conv;
    }

    @Test
    void conversion_cohort_self_join_enforces_window() {
        cohortConvMetric(Map.of());
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("paid_conv"));
        r.setDimensions(List.of("region"));
        CompiledQuery q = compiler.compile(salesModel, r, user);

        // Cohort entry = first base event per (entity, dim); conversion = EXISTS in window.
        assertThat(q.getSql()).contains("MIN(order_date) AS amos_et");
        assertThat(q.getSql()).contains("GROUP BY customer_id, region_code");
        assertThat(q.getSql()).contains("AND (status = 'paid')");
        assertThat(q.getSql()).contains("AND (status = 'trial')");
        assertThat(q.getSql()).contains("cv.order_date < b.amos_et + (?::bigint * INTERVAL '1 second')");
        assertThat(q.getSql()).contains("COUNT(DISTINCT CASE WHEN t.amos_cv THEN t.amos_ce END)::numeric");
        // Immature cohorts (window not closed) stay out of the denominator.
        assertThat(q.getSql()).contains("b.amos_et + (?::bigint * INTERVAL '1 second') <= CURRENT_DATE");
        // EXISTS params → maturity window → base side; region RLS fires on both sides.
        assertThat(q.getParams()).containsExactly(
                7L, 2592000L, "CN", "US",
                7L, "CN", "US",
                2592000L);
    }

    @Test
    void conversion_cohort_uses_time_range_end_as_as_of() {
        cohortConvMetric(Map.of());
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("paid_conv"));
        r.setDimensions(List.of("channel"));
        SemanticQueryRequest.TimeRange tr = new SemanticQueryRequest.TimeRange();
        tr.setField("order_date");
        tr.setPreset("custom");
        tr.setFrom("2026-01-01");
        tr.setTo("2026-09-12");
        r.setTimeRange(tr);
        CompiledQuery q = compiler.compile(salesModel, r, user);

        assertThat(q.getSql()).contains("order_date BETWEEN ?::date AND ?::date");
        assertThat(q.getSql()).contains("b.amos_et + (?::bigint * INTERVAL '1 second') <= ?::date");
        // RLS enforces conservatively even when the request does not touch a target dim.
        assertThat(q.getParams()).containsExactly(
                7L, 2592000L, "CN", "US",
                7L, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-09-12"), "CN", "US",
                2592000L, LocalDate.parse("2026-09-12"));
    }

    @Test
    void conversion_cannot_mix_with_other_metric_types() {
        cohortConvMetric(Map.of());
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("paid_conv", "total_sales"));
        assertThatThrownBy(() -> compiler.compile(salesModel, r, user))
                .isInstanceOf(MetricCompileException.class)
                .extracting("errorCode").isEqualTo("UNSUPPORTED_METRIC_COMBINATION");
    }

    @Test
    void conversion_without_cohort_filters_fails_closed() {
        cohortConvMetric(Map.of("base_filter", "", "conversion_filter", ""));
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("paid_conv"));
        assertThatThrownBy(() -> compiler.compile(salesModel, r, user))
                .isInstanceOf(MetricCompileException.class)
                .extracting("errorCode").isEqualTo("COHORT_FILTER_MISSING");
    }

    @Test
    void conversion_with_invalid_window_fails() {
        cohortConvMetric(Map.of("window", "30x"));
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("paid_conv"));
        assertThatThrownBy(() -> compiler.compile(salesModel, r, user))
                .isInstanceOf(MetricCompileException.class)
                .extracting("errorCode").isEqualTo("TIMERANGE_INVALID");
    }

    // ---- 7. RLS injection ----------------------------------------------------

    @Test
    void rls_appends_user_filter_and_params() {
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("total_sales"));
        r.setDimensions(List.of("region"));
        CompiledQuery q = compiler.compile(salesModel, r, user);
        assertThat(q.getSql()).contains("region_code IN (?, ?)");
        assertThat(q.getParams()).contains("CN", "US");
    }

    // ---- 8. unknown metric ---------------------------------------------------

    @Test
    void unknown_metric_throws() {
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("nonexistent_metric"));
        assertThatThrownBy(() -> compiler.compile(salesModel, r, user))
                .isInstanceOf(MetricCompileException.class)
                .extracting("errorCode").isEqualTo("UNKNOWN_METRIC");
    }

    // ---- 9. unknown dimension ------------------------------------------------

    @Test
    void unknown_dimension_throws() {
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("total_sales"));
        r.setDimensions(List.of("does_not_exist"));
        assertThatThrownBy(() -> compiler.compile(salesModel, r, user))
                .isInstanceOf(MetricCompileException.class)
                .extracting("errorCode").isEqualTo("UNKNOWN_DIMENSION");
    }

    // ---- 10. time range presets ----------------------------------------------

    @Test
    void time_range_ytd_emits_between() {
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("total_sales"));
        r.setTimeRange(new SemanticQueryRequest.TimeRange("order_date", "ytd", null, null));
        CompiledQuery q = compiler.compile(salesModel, r, user);
        assertThat(q.getSql()).contains("order_date BETWEEN ? AND ?");
        // ytd should produce a from = jan 1 of current year
        LocalDate today = LocalDate.now();
        assertThat(q.getParams()).contains(LocalDate.of(today.getYear(), 1, 1));
    }

    @Test
    void time_range_mtd_and_last_7_days() {
        // mtd
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("total_sales"));
        r.setTimeRange(new SemanticQueryRequest.TimeRange("order_date", "mtd", null, null));
        CompiledQuery q = compiler.compile(salesModel, r, user);
        LocalDate today = LocalDate.now();
        assertThat(q.getParams()).contains(today.withDayOfMonth(1));

        // last_7_days
        r.setTimeRange(new SemanticQueryRequest.TimeRange("order_date", "last_7_days", null, null));
        q = compiler.compile(salesModel, r, user);
        assertThat(q.getParams()).contains(today.minusDays(7));
    }

    // ---- 11. SQL injection via filter value never reaches SQL ---------------

    @Test
    void sql_injection_value_is_parameterised() {
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("total_sales"));
        r.setDimensions(List.of("region"));
        String evil = "'); DROP TABLE ord_sales_order; --";
        r.getFilters().add(new SemanticQueryRequest.Filter("region", "eq", evil));
        CompiledQuery q = compiler.compile(salesModel, r, user);
        // Evil string must be a parameter, never inlined in SQL.
        assertThat(q.getSql()).doesNotContain("DROP");
        assertThat(q.getParams()).contains(evil);
    }

    // ---- 11b. AccessPolicyCompiler denylist defence-in-depth ----------------

    @Test
    void access_policy_denylist_rejects_tampered_filter() {
        AccessPolicyCompiler apc = new AccessPolicyCompiler();
        AccessPolicyDTO evil = new AccessPolicyDTO();
        evil.setAccessGrant("evil");
        evil.setUserAttribute("dept");
        evil.setSqlFilter("region_code = {user.dept}; DROP TABLE ord_sales_order");
        StringBuilder where = new StringBuilder();
        assertThatThrownBy(() ->
                apc.injectRls(where, List.of(evil), List.of("region"),
                        new UserContext(1L, 1L, Map.of("dept", "FIN"))))
                .isInstanceOf(AccessException.class)
                .extracting("errorCode").isEqualTo("SQL_INJECTION_DETECTED");
    }

    // ---- 12. user attribute missing ------------------------------------------

    @Test
    void user_attribute_missing_throws() {
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("total_sales"));
        UserContext bare = new UserContext(1L, 1L, Map.of());
        assertThatThrownBy(() -> compiler.compile(salesModel, r, bare))
                .isInstanceOf(AccessException.class)
                .extracting("errorCode").isEqualTo("USER_ATTRIBUTE_MISSING");
    }

    // ---- 13. VizSuggester ----------------------------------------------------

    @Test
    void viz_suggester_recommends_line_when_time_dim() {
        DimensionDTO time = new DimensionDTO();
        time.setType("time");
        assertThat(VizSuggester.suggest(List.of(time), 1)).isEqualTo("line");
    }

    @Test
    void viz_suggester_recommends_bar_kpi_pivot() {
        DimensionDTO cat = new DimensionDTO();
        cat.setType("categorical");
        DimensionDTO cat2 = new DimensionDTO();
        cat2.setType("categorical");
        assertThat(VizSuggester.suggest(List.of(), 1)).isEqualTo("kpi");
        assertThat(VizSuggester.suggest(List.of(cat), 1)).isEqualTo("bar");
        assertThat(VizSuggester.suggest(List.of(cat, cat2), 2)).isEqualTo("pivot");
    }

    // ---- 14. order by + limit/offset -----------------------------------------

    @Test
    void order_limit_offset_appear_in_sql() {
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("total_sales"));
        r.setDimensions(List.of("region"));
        r.setOrder(List.of(new SemanticQueryRequest.OrderBy("total_sales", "desc")));
        r.setLimit(50);
        r.setOffset(100);
        CompiledQuery q = compiler.compile(salesModel, r, user);
        assertThat(q.getSql()).contains("ORDER BY \"sales.total_sales\" DESC");
        assertThat(q.getSql()).contains("LIMIT ? OFFSET ?");
        assertThat(q.getParams()).contains(50, 100);
    }

    // ---- 15. COUNT(*) measure expr -------------------------------------------

    @Test
    void count_star_measure_is_supported() {
        // order_count uses agg=COUNT, expr="*"
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("paid_conversion_rate"));
        CompiledQuery q = compiler.compile(salesModel, r, user);
        assertThat(q.getSql()).contains("COUNT(*)");
    }

    // ---- 16. time grain suffix on time dim -----------------------------------

    @Test
    void time_grain_suffix_emits_date_trunc() {
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("total_sales"));
        r.setDimensions(List.of("order_date__month"));
        CompiledQuery q = compiler.compile(salesModel, r, user);
        assertThat(q.getSql()).contains("DATE_TRUNC('month', order_date)");
        assertThat(q.getSql()).contains("\"sales.order_date__month\"");
    }

    // ---- 17. model-prefixed metric code still resolves -----------------------

    @Test
    void metric_code_with_model_prefix_resolves() {
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("sales.total_sales"));
        CompiledQuery q = compiler.compile(salesModel, r, user);
        assertThat(q.getSql()).contains("\"sales.total_sales\"");
    }

    // ---- 18. unsupported metric type ----------------------------------------

    @Test
    void unsupported_metric_type_throws() {
        MetricDTO bad = new MetricDTO();
        bad.setCode("bad");
        bad.setType("not_a_real_type");
        bad.setTypeParams(Map.of());
        salesModel.getMetrics().add(bad);
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("bad"));
        assertThatThrownBy(() -> compiler.compile(salesModel, r, user))
                .isInstanceOf(MetricCompileException.class)
                .extracting("errorCode").isEqualTo("UNSUPPORTED_METRIC_TYPE");
    }

    // ---- 19. UNKNOWN_MEASURE when measure code missing ----------------------

    @Test
    void unknown_measure_in_ratio_throws() {
        MetricDTO bad = new MetricDTO();
        bad.setCode("bad_ratio");
        bad.setType("ratio");
        bad.setTypeParams(Map.of("numerator", "nonexistent_measure", "denominator", "order_count"));
        salesModel.getMetrics().add(bad);
        SemanticQueryRequest r = req();
        r.setMetrics(List.of("bad_ratio"));
        assertThatThrownBy(() -> compiler.compile(salesModel, r, user))
                .isInstanceOf(MetricCompileException.class)
                .extracting("errorCode").isEqualTo("UNKNOWN_MEASURE");
    }

    // ---- 20. fingerprint stable across param values --------------------------

    @Test
    void fingerprint_stable_across_param_changes() {
        SemanticQueryRequest r1 = req();
        r1.setMetrics(List.of("total_sales"));
        r1.setDimensions(List.of("region"));
        r1.getFilters().add(new SemanticQueryRequest.Filter("region", "eq", "CN"));
        SemanticQueryRequest r2 = req();
        r2.setMetrics(List.of("total_sales"));
        r2.setDimensions(List.of("region"));
        r2.getFilters().add(new SemanticQueryRequest.Filter("region", "eq", "US"));
        assertThat(compiler.compile(salesModel, r1, user).getSqlFingerprint())
                .isEqualTo(compiler.compile(salesModel, r2, user).getSqlFingerprint());
    }

    // ---- 21. TimeRangeResolver invalid preset --------------------------------

    @Test
    void invalid_preset_throws() {
        SemanticQueryRequest.TimeRange tr = new SemanticQueryRequest.TimeRange(
                "order_date", "never_heard_of", null, null);
        assertThatThrownBy(() -> TimeRangeResolver.resolve(tr))
                .isInstanceOf(MetricCompileException.class)
                .extracting("errorCode").isEqualTo("TIMERANGE_INVALID");
    }
}
