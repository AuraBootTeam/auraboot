package com.auraboot.framework.integration.security.rbac;

import com.auraboot.framework.permission.constants.MetaPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-layer drift gate for the RBAC SOT matrix — pure, fast, no Spring / no DB.
 *
 * <p>The matrix ({@link RbacAccessMatrix}) is the INDEPENDENT declaration of intended access. This
 * test locks it against the two canonical sources it must stay consistent with, so a one-sided edit
 * (change bootstrap but not the matrix, or rename an enforcement constant) fails a fast unit test
 * instead of silently drifting:
 * <ul>
 *   <li>{@code tenant_member} allow set  ==  {@code default-bootstrap.json} tenant_member binding
 *       (intended baseline vs what bootstrap actually seeds).</li>
 *   <li>{@code REG-5-6-assignment} codes  ==  the {@link MetaPermission} constants the
 *       {@code PermissionInterceptor} gates for tenant_admin-only assignment (#1147).</li>
 * </ul>
 *
 * <p>Exhaustive per-code ENFORCEMENT (role×code → 200/403) and L1 resolution against a real DB are
 * separate slices that require a bootstrapped tenant (the bare integration-test DB has almost no
 * permission codes registered) — see {@code docs/agent-rules/rbac-golden-and-cross-cutting-regression.md}.
 */
@DisplayName("RBAC access matrix — static drift gate (matrix ↔ bootstrap ↔ MetaPermission)")
class RbacAccessMatrixConsistencyTest {

    private final RbacAccessMatrix matrix = RbacAccessMatrix.load();

    @Test
    @DisplayName("tenant_member allow set == default-bootstrap.json tenant_member binding")
    void tenantMemberAllowMatchesBootstrapBinding() {
        Set<String> matrixAllow = new LinkedHashSet<>(
                matrix.role("platform-baseline", "tenant_member").allow());
        Set<String> bootstrapBinding = bootstrapTenantMemberCodes();

        assertThat(matrixAllow)
                .as("matrix tenant_member allow set must equal the default-bootstrap.json binding; "
                        + "if they differ, the intended baseline and what new tenants actually get have drifted")
                .isEqualTo(bootstrapBinding);
    }

    @Test
    @DisplayName("REG-5-6 assignment codes == MetaPermission assignment constants gated by the interceptor")
    void assignmentCodesMatchInterceptorConstants() {
        Set<String> matrixCodes = new LinkedHashSet<>(matrix.specialRuleCodes("REG-5-6-assignment"));
        Set<String> enforced = Set.of(
                MetaPermission.USER_ROLE_MANAGE,
                MetaPermission.ROLE_MANAGE,
                MetaPermission.PERMISSION_MANAGE);

        assertThat(matrixCodes)
                .as("matrix REG-5/6 codes must equal exactly the codes PermissionInterceptor gates for "
                        + "tenant_admin-only assignment (ASSIGNMENT_ADMIN_ONLY_CODES, #1147)")
                .isEqualTo(enforced);
    }

    /** tenant_member permissionCodes from the bootstrap template on the classpath. */
    private static Set<String> bootstrapTenantMemberCodes() {
        try (InputStream in = RbacAccessMatrixConsistencyTest.class.getClassLoader()
                .getResourceAsStream("tenant-templates/default-bootstrap.json")) {
            assertThat(in).as("default-bootstrap.json must be on the classpath").isNotNull();
            JsonNode root = new ObjectMapper().readTree(in);
            Set<String> codes = new LinkedHashSet<>();
            for (JsonNode binding : root.path("rolePermissionBindings")) {
                if ("tenant_member".equals(binding.path("roleCode").asText())) {
                    binding.path("permissionCodes").forEach(n -> codes.add(n.asText()));
                }
            }
            assertThat(codes)
                    .as("default-bootstrap.json must declare a tenant_member binding")
                    .isNotEmpty();
            return codes;
        } catch (Exception e) {
            throw new IllegalStateException("failed to read default-bootstrap.json", e);
        }
    }
}
