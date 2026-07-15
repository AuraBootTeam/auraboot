package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.dto.AggregateQueryRequest.FilterConfig;
import com.auraboot.framework.meta.dto.MetricConfig;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.NamedQueryFieldMapper;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.meta.service.DataDomainService;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the OR/nested-group filter tree and relative-time value support added to
 * {@link AggregateQueryServiceImpl} (arsenal-show G4, P1). These exercise the pure SQL builder
 * via Mockito, asserting on the generated SQL structure + bound parameters — no DB / bootRun.
 *
 * <p>Injection safety is the focus of {@link #maliciousValueIsBoundNotConcatenated()} and
 * {@link #maliciousFieldNameRejected()}: every comparison value must travel as a bound
 * {@code #{params.*}} parameter, never spliced into the SQL text.
 */
@ExtendWith(MockitoExtension.class)
class AggregateQueryFilterTreeTest {

    private static final Long TENANT_ID = 10L;
    private static final Long USER_ID = 20L;

    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private NamedQueryMapper namedQueryMapper;
    @Mock private NamedQueryFieldMapper namedQueryFieldMapper;
    @Mock private MetaModelService metaModelService;
    @Mock private DataPermissionEngine dataPermissionEngine;
    @Mock private DataDomainService dataDomainService;

    @InjectMocks
    private AggregateQueryServiceImpl service;

    /** Result of a captured aggregate/named-query execution: the SQL text + the bound params. */
    private record Captured(String sql, Map<String, Object> params) {}

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, USER_ID, "user-pid", "tester");
        // Pin "now" to 2026-07-15 (a Wednesday) so relative-time windows are deterministic.
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(LocalDate.of(2026, 7, 15).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
        lenient().when(metaModelService.getTableName(anyString())).thenReturn("orders");
        lenient().when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("c", 1L)));
        lenient().when(dynamicDataMapper.selectByQueryWithoutTenant(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("c", 1L)));
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    // ==================== helpers ====================

    private static FilterConfig leaf(String field, String op, Object value) {
        FilterConfig f = new FilterConfig();
        f.setField(field);
        f.setOperator(op);
        f.setValue(value);
        return f;
    }

    private static FilterConfig group(String logic, FilterConfig... children) {
        FilterConfig g = new FilterConfig();
        g.setLogic(logic);
        g.setChildren(new ArrayList<>(List.of(children)));
        return g;
    }

    private AggregateQueryRequest aggregate(List<FilterConfig> filters) {
        MetricConfig metric = new MetricConfig();
        metric.setField("id");
        metric.setAggregation("count");
        metric.setAlias("total");
        AggregateQueryRequest req = new AggregateQueryRequest();
        req.setModelCode("orders");
        req.setMetrics(List.of(metric));
        req.setFilters(filters);
        return req;
    }

    @SuppressWarnings("unchecked")
    private Captured runAggregate(AggregateQueryRequest req) {
        // Bypass data-permission projection so the WHERE clause is just the filters (cleaner assert).
        MetaContext.runWithoutDataPermission(() -> service.execute(req));
        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> pCap = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).selectByQuery(sqlCap.capture(), pCap.capture());
        return new Captured(sqlCap.getValue(), (Map<String, Object>) pCap.getValue());
    }

    // ==================== AND / OR / nested ====================

    @Test
    @DisplayName("top-level filter list is AND-joined (backward compatible)")
    void topLevelListIsAnded() {
        Captured c = runAggregate(aggregate(List.of(
                leaf("status", "eq", "PAID"),
                leaf("region", "eq", "East"))));

        assertThat(c.sql()).contains("status = #{params.f_0} AND region = #{params.f_1}");
        assertThat(c.params()).containsEntry("f_0", "PAID").containsEntry("f_1", "East");
    }

    @Test
    @DisplayName("an OR group renders parenthesised with unique param keys per leaf")
    void orGroupRenders() {
        Captured c = runAggregate(aggregate(List.of(
                group("or",
                        leaf("region", "eq", "East"),
                        leaf("region", "eq", "West")))));

        assertThat(c.sql()).contains("(region = #{params.f_0} OR region = #{params.f_1})");
        // Same column used twice must NOT collide — distinct keys, both values bound.
        assertThat(c.params()).containsEntry("f_0", "East").containsEntry("f_1", "West");
    }

    @Test
    @DisplayName("nested group: A AND (B OR C)")
    void nestedAndOfOr() {
        Captured c = runAggregate(aggregate(List.of(
                leaf("status", "eq", "PAID"),
                group("or",
                        leaf("region", "eq", "East"),
                        group("and",
                                leaf("amount", "gte", 100),
                                leaf("amount", "lt", 500))))));

        assertThat(c.sql()).contains(
                "status = #{params.f_0} AND "
                        + "(region = #{params.f_1} OR "
                        + "(amount >= #{params.f_2} AND amount < #{params.f_3}))");
        assertThat(c.params())
                .containsEntry("f_0", "PAID")
                .containsEntry("f_1", "East")
                .containsEntry("f_2", 100)
                .containsEntry("f_3", 500);
    }

    @Test
    @DisplayName("like value is wrapped and bound; in-list value is bound")
    void likeAndInBind() {
        Captured c = runAggregate(aggregate(List.of(
                group("or",
                        leaf("name", "like", "wid"),
                        leaf("region", "in", List.of("East", "West"))))));

        assertThat(c.sql()).contains("(name LIKE #{params.f_0} OR region IN (#{params.f_1}))");
        assertThat(c.params()).containsEntry("f_0", "%wid%");
        assertThat(c.params().get("f_1")).isEqualTo(List.of("East", "West"));
    }

    @Test
    @DisplayName("is_null / is_not_null render without a bound value")
    void nullChecksBindNothing() {
        Captured c = runAggregate(aggregate(List.of(
                group("or",
                        leaf("closed_at", "is_null", null),
                        leaf("owner", "is_not_null", null)))));

        assertThat(c.sql()).contains("(closed_at IS NULL OR owner IS NOT NULL)");
        assertThat(c.params()).doesNotContainKey("f_0").doesNotContainKey("f_1");
    }

    // ==================== relative time ====================

    @Test
    @DisplayName("relative 'today' resolves to a half-open [today, tomorrow) bound-parameter range")
    void relativeToday() {
        Captured c = runAggregate(aggregate(List.of(
                leaf("created_at", "relative", "today"))));

        assertThat(c.sql()).contains(
                "(created_at >= #{params.f_0_lo} AND created_at < #{params.f_0_hi})");
        assertThat(c.params()).containsEntry("f_0_lo", LocalDate.of(2026, 7, 15));
        assertThat(c.params()).containsEntry("f_0_hi", LocalDate.of(2026, 7, 16));
    }

    @Test
    @DisplayName("relative windows: last_7_days / this_month / this_quarter / this_year")
    void relativeWindows() {
        assertRange("last_7_days", LocalDate.of(2026, 7, 9), LocalDate.of(2026, 7, 16));
        assertRange("last_30_days", LocalDate.of(2026, 6, 16), LocalDate.of(2026, 7, 16));
        assertRange("yesterday", LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 15));
        assertRange("this_month", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1));
        assertRange("this_quarter", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1));
        assertRange("this_year", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
    }

    private void assertRange(String token, LocalDate expectedLo, LocalDate expectedHi) {
        Captured c = runAggregateFresh(aggregate(List.of(
                leaf("created_at", "relative", token))));
        assertThat(c.params()).as("lo for %s", token).containsEntry("f_0_lo", expectedLo);
        assertThat(c.params()).as("hi for %s", token).containsEntry("f_0_hi", expectedHi);
    }

    @Test
    @DisplayName("relative this_week starts on Monday and spans exactly 7 days containing today")
    void relativeThisWeek() {
        Captured c = runAggregate(aggregate(List.of(
                leaf("created_at", "relative", "this_week"))));
        LocalDate lo = (LocalDate) c.params().get("f_0_lo");
        LocalDate hi = (LocalDate) c.params().get("f_0_hi");
        assertThat(lo.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(hi).isEqualTo(lo.plusWeeks(1));
        LocalDate today = LocalDate.of(2026, 7, 15);
        assertThat(!today.isBefore(lo) && today.isBefore(hi)).isTrue();
    }

    @Test
    @DisplayName("relative {relative:last_n_days,n:14} resolves n from the object form")
    void relativeParameterizedN() {
        Captured c = runAggregate(aggregate(List.of(
                leaf("created_at", "relative", Map.of("relative", "last_n_days", "n", 14)))));
        assertThat(c.params()).containsEntry("f_0_lo", LocalDate.of(2026, 7, 2));
        assertThat(c.params()).containsEntry("f_0_hi", LocalDate.of(2026, 7, 16));
    }

    // ==================== injection red-line ====================

    @Test
    @DisplayName("INJECTION: a malicious value is bound as a parameter, never spliced into SQL")
    void maliciousValueIsBoundNotConcatenated() {
        String payload = "'; DROP TABLE ab_named_query; --";
        Captured c = runAggregate(aggregate(List.of(
                leaf("status", "eq", payload))));

        // The value travels only as a bound parameter…
        assertThat(c.sql()).contains("status = #{params.f_0}");
        assertThat(c.params()).containsEntry("f_0", payload);
        // …and never as literal SQL text.
        assertThat(c.sql()).doesNotContain("DROP TABLE");
        assertThat(c.sql()).doesNotContain("--");
        assertThat(c.sql()).doesNotContain(";");
        // The generated statement is provably a safe read-only SELECT (would throw if DROP leaked in).
        SqlSafetyUtils.validateSelectOnlySql(c.sql());
    }

    @Test
    @DisplayName("INJECTION: a malicious field name is rejected before any SQL is composed")
    void maliciousFieldNameRejected() {
        AggregateQueryRequest req = aggregate(List.of(
                leaf("status; DROP TABLE x", "eq", "x")));

        assertThatThrownBy(() -> MetaContext.runWithoutDataPermission(() -> service.execute(req)))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Invalid filter field");
    }

    @Test
    @DisplayName("INJECTION: a bogus relative-time token is rejected, never reaching SQL")
    void maliciousRelativeTokenRejected() {
        AggregateQueryRequest req = aggregate(List.of(
                leaf("created_at", "relative", "today'; DROP TABLE x; --")));

        assertThatThrownBy(() -> MetaContext.runWithoutDataPermission(() -> service.execute(req)))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Unsupported relative time token");
    }

    // ==================== structural guards ====================

    @Test
    @DisplayName("a node that is both leaf (field) and group (children) is rejected")
    void leafAndGroupAmbiguityRejected() {
        FilterConfig bad = group("or", leaf("region", "eq", "East"));
        bad.setField("status"); // now ambiguous: has both field and children

        AggregateQueryRequest req = aggregate(List.of(bad));
        assertThatThrownBy(() -> MetaContext.runWithoutDataPermission(() -> service.execute(req)))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("cannot be both a leaf");
    }

    @Test
    @DisplayName("an unknown group logic (not and/or) is rejected")
    void invalidGroupLogicRejected() {
        AggregateQueryRequest req = aggregate(List.of(
                group("xor", leaf("a", "eq", 1), leaf("b", "eq", 2))));
        assertThatThrownBy(() -> MetaContext.runWithoutDataPermission(() -> service.execute(req)))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Invalid filter group logic");
    }

    @Test
    @DisplayName("filter nesting deeper than the max depth is rejected")
    void tooDeepNestingRejected() {
        FilterConfig node = leaf("a", "eq", 1);
        for (int i = 0; i < 12; i++) {
            node = group("and", node);
        }
        FilterConfig finalNode = node;
        assertThatThrownBy(() -> MetaContext.runWithoutDataPermission(
                () -> service.execute(aggregate(List.of(finalNode)))))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("nesting exceeds max depth");
    }

    // ==================== named-query path parity ====================

    @Test
    @DisplayName("named-query path: OR group maps each leaf to its whitelisted columnExpr and binds values")
    void namedQueryOrGroup() {
        NamedQuery query = new NamedQuery();
        query.setTenantId(TENANT_ID);
        query.setCode("orders_nq");
        query.setStatus("draft");
        query.setFromSql("mt_orders WHERE tenant_id = #{params.tenantId}");
        lenient().when(namedQueryMapper.findByCode("orders_nq")).thenReturn(query);
        lenient().when(namedQueryFieldMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new NamedQueryField(TENANT_ID, "orders_nq", "status", "o.status", "string"),
                        new NamedQueryField(TENANT_ID, "orders_nq", "region", "o.region", "string")));

        AggregateQueryRequest req = new AggregateQueryRequest();
        req.setType("namedQuery");
        req.setQueryCode("orders_nq");
        req.setFilters(List.of(
                group("or",
                        leaf("status", "eq", "PAID"),
                        leaf("region", "eq", "East"))));

        MetaContext.runWithoutDataPermission(() -> service.execute(req));

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> pCap = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).selectByQueryWithoutTenant(sqlCap.capture(), pCap.capture());

        assertThat(sqlCap.getValue())
                .contains("(o.status = #{params.nqf_0} OR o.region = #{params.nqf_1})");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) pCap.getValue();
        assertThat(params).containsEntry("nqf_0", "PAID").containsEntry("nqf_1", "East");
    }

    @Test
    @DisplayName("named-query path: a filter field outside the whitelist is rejected")
    void namedQueryNonWhitelistedFieldRejected() {
        NamedQuery query = new NamedQuery();
        query.setTenantId(TENANT_ID);
        query.setCode("orders_nq");
        query.setStatus("draft");
        query.setFromSql("mt_orders WHERE tenant_id = #{params.tenantId}");
        lenient().when(namedQueryMapper.findByCode("orders_nq")).thenReturn(query);
        lenient().when(namedQueryFieldMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new NamedQueryField(TENANT_ID, "orders_nq", "status", "o.status", "string")));

        AggregateQueryRequest req = new AggregateQueryRequest();
        req.setType("namedQuery");
        req.setQueryCode("orders_nq");
        req.setFilters(List.of(
                group("or",
                        leaf("status", "eq", "PAID"),
                        leaf("secret_column", "eq", "x")))); // not in whitelist

        Throwable t = catchThrowable(() ->
                MetaContext.runWithoutDataPermission(() -> service.execute(req)));
        assertThat(t).isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("not in whitelist");
    }

    // ==================== fresh-verify helper for the multi-token relative test ====================

    /**
     * Like {@link #runAggregate} but tolerant of being called several times in one test — each call
     * re-stubs and verifies the most-recent interaction. Used only by {@link #relativeWindows()}.
     */
    @SuppressWarnings("unchecked")
    private Captured runAggregateFresh(AggregateQueryRequest req) {
        org.mockito.Mockito.clearInvocations(dynamicDataMapper);
        MetaContext.runWithoutDataPermission(() -> service.execute(req));
        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> pCap = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).selectByQuery(sqlCap.capture(), pCap.capture());
        return new Captured(sqlCap.getValue(), (Map<String, Object>) pCap.getValue());
    }
}
