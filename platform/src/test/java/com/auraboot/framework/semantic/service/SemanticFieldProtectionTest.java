package com.auraboot.framework.semantic.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldMaskRule;
import com.auraboot.framework.meta.entity.FieldMaskConfig;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.FieldMaskService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.semantic.compiler.*;
import com.auraboot.framework.semantic.entity.AbSemanticModel;
import com.auraboot.framework.semantic.mapper.*;
import com.auraboot.framework.semantic.parser.*;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hermetic service regressions with the real parser, compiler, and field guard. */
class SemanticFieldProtectionTest {
    private static final String YAML = """
            version: "0.1"
            semantic_model: {code: g, model_ref: source_record, primary_entity: id}
            entities: [{name: id, type: primary, field_ref: id}]
            dimensions: [{code: status, type: categorical, field_ref: status}]
            measures: [{code: money, agg: SUM, expr: "COALESCE(amount, discount)"}]
            metrics:
              - {code: total, type: simple, type_params: {measure: money}, filter: "secret_score > 0"}
              - {code: derived, type: derived, type_params: {expr: "{total} + MAX(secret_cost)"}}
            """;
    private final UserContext user = new UserContext(42L, 1L, null);
    private MetaContext.Snapshot previous;
    private DataPermissionEngine policies;
    private FieldMaskService masks;
    private MetaModelService models;
    private JdbcTemplate jdbc;
    private TenantMemberService members;
    private SemanticQueryService service;

    @BeforeEach void setup() {
        previous = MetaContext.snapshot();
        MetaContext.clear();
        MetaContext.setContext(1L, 42L, "caller", "caller");
        MetaContext.setMemberId(142L);
        policies = mock(DataPermissionEngine.class);
        masks = mock(FieldMaskService.class);
        models = mock(MetaModelService.class);
        jdbc = mock(JdbcTemplate.class);
        members = mock(TenantMemberService.class);
        var member = new TenantMember(); member.setId(142L); member.setTenantId(1L);
        member.setUserId(42L); member.setStatus("ACTIVE");
        when(members.findByTenantIdAndUserId(1L, 42L)).thenReturn(member);
        when(models.getTableName("source_record")).thenReturn("mt_source_record");
        when(policies.getFieldMaskRules(1L, "source_record", 42L)).thenReturn(List.of());
        when(masks.getEffectiveConfigs("source_record", 42L, "semantic")).thenReturn(List.of());
        var modelMapper = mock(AbSemanticModelMapper.class);
        var row = new AbSemanticModel(); row.setCode("g"); row.setYamlSource(YAML);
        when(modelMapper.listActiveByTenant(1L)).thenReturn(List.of(row));
        service = new SemanticQueryService(new SemanticYamlParser(), new SemanticYamlValidator(),
                new MetricCompiler(new AccessPolicyCompiler()), modelMapper,
                mock(AbSemanticMetricMapper.class), mock(AbSemanticQueryLogMapper.class), models,
                mock(UserPermissionService.class), new SemanticFieldProtection(policies, masks, models, members));
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);
    }
    @AfterEach void restore() { MetaContext.clear(); MetaContext.restore(previous); }

    private SemanticQueryRequest request() {
        var req = new SemanticQueryRequest(); req.setMetrics(List.of("g.derived"));
        req.setDimensions(List.of("status")); return req;
    }
    private void call(String entry) {
        switch (entry) {
            case "query" -> service.executeQuery(request(), user);
            case "sql" -> service.explainQuery(request(), user);
            case "dry-run" -> service.validateQuery(request(), user);
            default -> throw new IllegalArgumentException(entry);
        }
    }
    private void protect(String column) {
        when(policies.getFieldMaskRules(1L, "source_record", 42L))
                .thenReturn(List.of(FieldMaskRule.builder().fieldCode("sensitive").maskType("HIDE").build()));
        when(models.getColumnName("source_record", "sensitive")).thenReturn(column);
    }
    @ParameterizedTest @ValueSource(strings = {"query", "sql", "dry-run"})
    void everySharedEntryRejectsProtectedExpressionBeforeJdbc(String entry) {
        protect("amount");
        assertThatThrownBy(() -> call(entry)).isInstanceOf(AccessDeniedException.class)
                .hasMessage("Protected source fields cannot be used in semantic queries");
        verifyNoInteractions(jdbc);
    }
    @ParameterizedTest @ValueSource(strings = {"discount", "secret_score", "secret_cost", "status", "AMOUNT"})
    void protectsExpressionFilterDerivedDimensionAndCaseFoldedColumns(String column) {
        protect(column);
        assertThatThrownBy(() -> call("sql")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(jdbc);
    }
    @Test void honorsEffectiveConfigurationsForAggregateAndExportInference() {
        var config = new FieldMaskConfig(); config.setFieldCode("sensitive");
        when(masks.getEffectiveConfigs("source_record", 42L, "semantic")).thenReturn(List.of(config));
        when(models.getColumnName("source_record", "sensitive")).thenReturn("secret_score");
        assertThatThrownBy(() -> call("sql")).isInstanceOf(AccessDeniedException.class);
        verify(policies).getFieldMaskRules(1L, "source_record", 42L);
        verifyNoInteractions(jdbc);
    }
    @Test void unusedProtectedColumnsDoNotDenyAnAuthorizedMetric() {
        protect("private_notes");
        var result = service.explainQuery(request(), user);
        assertThat(result.getSql()).contains("FROM mt_source_record", "SUM(COALESCE(amount, discount))");
        assertThat(result.getReferencedColumns()).contains("secret_cost", "secret_score", "status", "amount");
        verify(masks).getEffectiveConfigs("source_record", 42L, "semantic");
        verifyNoInteractions(jdbc);
    }
    @Test void callerWithNoApplicableProtectionReceivesCompiledQuery() {
        // No applicable protection must not introduce an extra raw-model read requirement.
        assertThat(service.explainQuery(request(), user).getSql()).contains("FROM mt_source_record");
        verify(masks).getEffectiveConfigs("source_record", 42L, "semantic");
        verifyNoInteractions(jdbc);
    }
    @Test void unresolvedProtectedMappingFailsClosed() {
        protect(null);
        assertThatThrownBy(() -> call("sql")).isInstanceOf(AccessDeniedException.class)
                .hasMessage("Protected field has no physical column mapping");
        verifyNoInteractions(jdbc);
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "inactive", "deleted", "wrong-member"})
    void membershipCannotBypassColumnPolicy(String state) {
        var member = new TenantMember(); member.setId(142L); member.setTenantId(1L);
        member.setUserId(42L); member.setStatus(state.equals("inactive") ? "INACTIVE" : "ACTIVE");
        member.setDeletedFlag(state.equals("deleted"));
        when(members.findByTenantIdAndUserId(1L, 42L)).thenReturn(state.equals("missing") ? null : member);
        if (state.equals("wrong-member")) MetaContext.setMemberId(999L);
        assertThatThrownBy(() -> call("sql")).isInstanceOf(AccessDeniedException.class)
                .hasMessage("Semantic field protection requires active caller membership");
        verifyNoInteractions(policies, masks, jdbc);
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "tenant", "user"})
    void identityMismatchCannotConsultAnotherCallersMaskContext(String mismatch) {
        MetaContext.clear();
        if (!mismatch.equals("missing")) MetaContext.setContext(mismatch.equals("tenant") ? 2L : 1L,
                mismatch.equals("user") ? 43L : 42L, "other", "other");
        assertThatThrownBy(() -> call("sql")).isInstanceOf(AccessDeniedException.class)
                .hasMessage("Semantic field protection requires matching caller context");
        verifyNoInteractions(policies, masks, jdbc);
    }
}
