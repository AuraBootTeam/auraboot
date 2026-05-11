package com.auraboot.framework.aurabot.skill.provider;

import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * IT for {@link AuraBotSkillToolProvider} (C-5 Task 2 §4.1).
 *
 * <p>Mirrors the {@link com.auraboot.framework.aurabot.skill.AuraBotSkillControllerIntegrationTest}
 * mocking pattern: {@link UserPermissionService} + {@link PermissionMapper} are
 * mocked so each case can drive a deterministic permission set without
 * provisioning real role rows. The SkillRegistry, MessageSource and provider
 * itself are real Spring beans on the {@code skills-c2-test} stack (real
 * PostgreSQL + Redis).
 */
@ActiveProfiles({"integration-test", "skills-c2-test"})
class AuraBotSkillToolProviderIntegrationTest extends BaseIntegrationTest {

    @Autowired AuraBotSkillToolProvider provider;

    @MockBean private UserPermissionService userPermissionService;
    @MockBean private PermissionMapper permissionMapper;

    /** Per-test permission code set the mocked PermissionMapper materialises. */
    private final Set<String> currentPermissions = new HashSet<>();

    @BeforeEach
    void setUp() {
        currentPermissions.clear();
        MetaContext.setContext(getTestTenant().getId(), 1L, null, "it-c5-user");

        // Any non-null userId resolves to a non-empty id list so the mapper is
        // consulted; mapper materialises Permission rows from currentPermissions.
        when(userPermissionService.getUserPermissionIds(eq(1L)))
                .thenAnswer(inv -> Set.of(1L));
        when(permissionMapper.findByIds(any())).thenAnswer(inv ->
                currentPermissions.stream().map(code -> {
                    Permission p = new Permission();
                    p.setCode(code);
                    return p;
                }).toList());
    }

    @Test
    @DisplayName("providerCode returns 'aurabot'")
    void providerCode_aurabot() {
        assertThat(provider.providerCode()).isEqualTo("aurabot");
    }

    @Test
    @DisplayName("discover returns all registered skills with aurabot: prefix")
    void discover_returnsAllSkillsWithPrefix() {
        // Grant the union of perms required by all built-in skills so the full
        // catalog surfaces.
        currentPermissions.addAll(Set.of(
                "meta.model.read", "meta.model.update", "meta.field.update"));

        ToolDiscoveryContext ctx = ToolDiscoveryContext.builder()
                .tenantId(getTestTenant().getId())
                .userId(1L)
                .build();

        List<ToolDefinition> tools = provider.discover(ctx);

        assertThat(tools).isNotEmpty();
        assertThat(tools).allMatch(t -> t.getToolCode().startsWith("aurabot:"));
        assertThat(tools).extracting(ToolDefinition::getToolName)
                .contains("model:query"); // LOW skill, requires meta.model.read
    }

    @Test
    @DisplayName("discover filters by user permissions — model:create absent without meta.model.update")
    void discover_filtersByPermission() {
        // Empty perms: model:create / field:add (which need extra perms) must NOT surface.
        // (echo / no-perm skills are still allowed; we only assert exclusions.)
        currentPermissions.clear();

        ToolDiscoveryContext ctx = ToolDiscoveryContext.builder()
                .tenantId(getTestTenant().getId())
                .userId(1L)
                .build();

        List<ToolDefinition> tools = provider.discover(ctx);

        assertThat(tools).extracting(ToolDefinition::getToolName)
                .doesNotContain("model:create", "field:add");
    }

    @Test
    @DisplayName("ToolDefinition risk + confirmation flags reflect skill metadata")
    void discover_riskMappingCorrect() {
        currentPermissions.addAll(Set.of(
                "meta.model.read", "meta.model.update", "meta.field.update"));

        ToolDiscoveryContext ctx = ToolDiscoveryContext.builder()
                .tenantId(getTestTenant().getId()).userId(1L).build();

        List<ToolDefinition> tools = provider.discover(ctx);

        ToolDefinition modelCreate = tools.stream()
                .filter(t -> "model:create".equals(t.getToolName()))
                .findFirst().orElseThrow();
        assertThat(modelCreate.getRiskLevel()).isEqualTo("HIGH");
        assertThat(modelCreate.isRequiresApproval()).isTrue();
        assertThat(modelCreate.isRequiresConfirmation()).isTrue();
        assertThat(modelCreate.getToolType()).isEqualTo("AURABOT_SKILL");

        ToolDefinition modelQuery = tools.stream()
                .filter(t -> "model:query".equals(t.getToolName()))
                .findFirst().orElseThrow();
        assertThat(modelQuery.getRiskLevel()).isEqualTo("LOW");
        assertThat(modelQuery.isRequiresApproval()).isFalse();
    }

    @Test
    @DisplayName("handles() recognizes aurabot: prefix")
    void handles_aurabotPrefix() {
        assertThat(provider.handles("aurabot:model:create")).isTrue();
        assertThat(provider.handles("aurabot:field:add")).isTrue();
        assertThat(provider.handles("cmd:foo")).isFalse();
        assertThat(provider.handles("platform.bar")).isFalse();
        assertThat(provider.handles(null)).isFalse();
    }
}
