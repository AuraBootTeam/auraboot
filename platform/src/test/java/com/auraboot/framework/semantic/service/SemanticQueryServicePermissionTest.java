package com.auraboot.framework.semantic.service;

import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.semantic.compiler.MetricCompileException;
import com.auraboot.framework.semantic.compiler.MetricCompiler;
import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.semantic.entity.AbSemanticModel;
import com.auraboot.framework.semantic.mapper.AbSemanticMetricMapper;
import com.auraboot.framework.semantic.mapper.AbSemanticModelMapper;
import com.auraboot.framework.semantic.mapper.AbSemanticQueryLogMapper;
import com.auraboot.framework.semantic.parser.SemanticYamlParser;
import com.auraboot.framework.semantic.parser.SemanticYamlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression net for the "declared-but-never-enforced" metric permission gap:
 * a metric's {@code required_permissions} must be checked against the caller
 * BEFORE any SQL is compiled or executed.
 *
 * <p>Uses the real {@link SemanticYamlParser} + {@link SemanticYamlValidator} +
 * {@link MetricCompiler} (pure functions) and mocks only the DB mappers and the
 * {@link UserPermissionService}, so the assertion exercises the real enforcement
 * path in {@link SemanticQueryService#executeQuery}. JdbcTemplate is left null —
 * a denied request must never reach it.
 */
class SemanticQueryServicePermissionTest {

    private static final String YAML = ""
            + "version: \"0.1\"\n"
            + "semantic_model: {code: g, model_ref: ab_role, primary_entity: id}\n"
            + "entities: [{name: id, type: primary, field_ref: id}]\n"
            + "dimensions: [{code: status, type: categorical, field_ref: status}]\n"
            + "measures: [{code: c, agg: COUNT, expr: \"*\"}]\n"
            + "metrics:\n"
            + "  - {code: open, type: simple, type_params: {measure: c}}\n"
            + "  - {code: secret, type: simple, type_params: {measure: c}, required_permissions: [finance.secret.view]}\n";

    private AbSemanticModelMapper modelMapper;
    private MetaModelService metaModelService;
    private UserPermissionService userPermissionService;
    private SemanticQueryService service;

    @BeforeEach
    void setup() {
        modelMapper = mock(AbSemanticModelMapper.class);
        metaModelService = mock(MetaModelService.class);
        userPermissionService = mock(UserPermissionService.class);
        when(metaModelService.getTableName("ab_role")).thenReturn("ab_role");

        AbSemanticModel row = new AbSemanticModel();
        row.setCode("g");
        row.setStatus("ACTIVE");
        row.setYamlSource(YAML);
        when(modelMapper.listActiveByTenant(1L)).thenReturn(List.of(row));

        service = new SemanticQueryService(
                new SemanticYamlParser(),
                new SemanticYamlValidator(),
                new MetricCompiler(new com.auraboot.framework.semantic.compiler.AccessPolicyCompiler()),
                modelMapper,
                mock(AbSemanticMetricMapper.class),
                mock(AbSemanticQueryLogMapper.class),
                metaModelService,
                userPermissionService);
        // JdbcTemplate intentionally not wired: a denied request must never reach it.
    }

    @Test
    void deniesMetricWhoseRequiredPermissionCallerLacks() {
        when(userPermissionService.hasPermission(42L, "finance.secret.view")).thenReturn(false);

        SemanticQueryRequest req = new SemanticQueryRequest();
        req.setMetrics(List.of("g.secret"));

        assertThatThrownBy(() -> service.executeQuery(req, new UserContext(42L, 1L, null)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("finance.secret.view");
    }

    @Test
    void allowsMetricWhoseRequiredPermissionCallerHolds() {
        when(userPermissionService.hasPermission(42L, "finance.secret.view")).thenReturn(true);

        SemanticQueryRequest req = new SemanticQueryRequest();
        req.setMetrics(List.of("g.secret"));

        // Compiles + (no JdbcTemplate) returns compiled SQL without throwing on permissions.
        assertThatCode(() -> service.explainQuery(req, new UserContext(42L, 1L, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsMetricWithNoRequiredPermissions() {
        SemanticQueryRequest req = new SemanticQueryRequest();
        req.setMetrics(List.of("g.open"));

        // No required_permissions declared -> never consults UserPermissionService, never throws.
        assertThatCode(() -> service.explainQuery(req, new UserContext(42L, 1L, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void resolvesGovernedModelCodeToPhysicalTableBeforeCompilation() {
        when(metaModelService.getTableName("ab_role")).thenReturn("mt_governed_role");

        SemanticQueryRequest req = new SemanticQueryRequest();
        req.setMetrics(List.of("g.open"));

        assertThat(service.explainQuery(req, new UserContext(42L, 1L, null)).getSql())
                .contains("FROM mt_governed_role")
                .doesNotContain("FROM ab_role");
    }

    @Test
    void rejectsModelRefThatIsNotAGovernedMetaModel() {
        when(metaModelService.getTableName("ab_role"))
                .thenThrow(new RuntimeException("model not found"));

        SemanticQueryRequest req = new SemanticQueryRequest();
        req.setMetrics(List.of("g.open"));

        assertThatThrownBy(() ->
                service.explainQuery(req, new UserContext(42L, 1L, null)))
                .isInstanceOf(MetricCompileException.class)
                .hasMessageContaining("does not resolve to a published MetaModel")
                .hasMessageContaining("ab_role");
    }
}
