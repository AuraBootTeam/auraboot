package com.auraboot.framework.semantic.service;

import com.auraboot.framework.semantic.compiler.MetricCompiler;
import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.semantic.entity.AbSemanticModel;
import com.auraboot.framework.semantic.mapper.AbSemanticMetricMapper;
import com.auraboot.framework.semantic.mapper.AbSemanticModelMapper;
import com.auraboot.framework.semantic.mapper.AbSemanticQueryLogMapper;
import com.auraboot.framework.semantic.parser.SemanticYamlParser;
import com.auraboot.framework.semantic.parser.SemanticYamlValidator;
import com.auraboot.framework.permission.service.UserPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
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
    private UserPermissionService userPermissionService;
    private SemanticQueryService service;

    @BeforeEach
    void setup() {
        modelMapper = mock(AbSemanticModelMapper.class);
        userPermissionService = mock(UserPermissionService.class);

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
}
