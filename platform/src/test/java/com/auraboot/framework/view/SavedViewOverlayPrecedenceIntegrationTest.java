package com.auraboot.framework.view;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.organization.entity.Team;
import com.auraboot.framework.organization.entity.TeamMember;
import com.auraboot.framework.organization.mapper.TeamMapper;
import com.auraboot.framework.organization.mapper.TeamMemberMapper;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.auraboot.framework.view.dto.SavedViewCreateRequest;
import com.auraboot.framework.view.entity.ViewConfig;
import com.auraboot.framework.view.entity.ViewConfig.ColumnConfig;
import com.auraboot.framework.view.service.SavedViewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Real-PostgreSQL proof of tenant → team → role → personal overlay precedence. */
class SavedViewOverlayPrecedenceIntegrationTest extends BaseIntegrationTest {

    @Autowired private SavedViewService savedViewService;
    @Autowired private TeamMapper teamMapper;
    @Autowired private TeamMemberMapper teamMemberMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private UserRoleMapper userRoleMapper;

    @Test
    void composesApplicableDefaultsWithoutLeakingAnotherUsersPersonalLayer() {
        String suffix = UniqueIdGenerator.generate().toLowerCase();
        String modelCode = "test_model";
        Team team = team(suffix);
        Role role = role(suffix);
        addCurrentMemberships(team, role);

        createDefault("global", null, null, "tenant", modelCode, 100, "default");
        createDefault("team", team.getPid(), null, "team", modelCode, 120, "compact");
        createDefault("role", null, role.getPid(), "role", modelCode, 140, "compact");
        createDefault("personal", null, null, "personal", modelCode, 160, "comfortable");

        var effective = savedViewService.getDefaultView(modelCode, null);
        assertThat(effective.getScope()).isEqualTo("personal");
        assertThat(effective.getViewConfig().getDensity()).isEqualTo("comfortable");
        assertThat(effective.getViewConfig().getColumns()).singleElement().satisfies(column -> {
            assertThat(column.getFieldCode()).isEqualTo("name");
            assertThat(column.getWidth()).isEqualTo(160);
        });

        var rows = savedViewService.getAccessibleViews(modelCode, null);
        var shared = rows.stream().filter(view -> "global".equals(view.getScope())).findFirst().orElseThrow();
        if (shared.getViewConfig().getMeta() == null) {
            shared.getViewConfig().setMeta(new ViewConfig.Meta());
        }
        shared.getViewConfig().getMeta().setOverlayStatus("STALE");
        shared.getViewConfig().getMeta().setOverlayReasonCodes(List.of("LOWER_LAYER_STALE"));
        shared.getViewConfig().getMeta().setOverlayStalePaths(List.of("/columns/obsolete"));
        savedViewService.update(shared.getPid(), updateConfig(shared.getViewConfig()));

        var staleEffective = savedViewService.getDefaultView(modelCode, null);
        assertThat(staleEffective.getViewConfig().getMeta().getOverlayStatus()).isEqualTo("STALE");
        assertThat(staleEffective.getViewConfig().getMeta().getOverlayReasonCodes())
                .contains("LOWER_LAYER_STALE");
        assertThat(staleEffective.getViewConfig().getMeta().getOverlayStalePaths())
                .contains("/columns/obsolete");

        MetaContext.Snapshot original = MetaContext.snapshot();
        try {
            MetaContext.setContext(
                    testTenant.getId(), testUser.getId() + 99_000,
                    UniqueIdGenerator.generate(), "isolated-user");
            MetaContext.setMemberId(null);
            MetaContext.setEnvironmentId(original.envId());
            var isolated = savedViewService.getDefaultView(modelCode, null);
            assertThat(isolated.getScope()).isEqualTo("global");
            assertThat(isolated.getViewConfig().getDensity()).isEqualTo("default");
            assertThat(isolated.getViewConfig().getColumns().getFirst().getWidth()).isEqualTo(100);
        } finally {
            MetaContext.restore(original);
        }
    }

    private com.auraboot.framework.view.dto.SavedViewUpdateRequest updateConfig(ViewConfig config) {
        var update = new com.auraboot.framework.view.dto.SavedViewUpdateRequest();
        update.setViewConfig(config);
        return update;
    }

    private void createDefault(
            String scope,
            String teamPid,
            String rolePid,
            String name,
            String modelCode,
            int width,
            String density) {
        SavedViewCreateRequest request = new SavedViewCreateRequest();
        request.setName(name + "-" + UniqueIdGenerator.generate());
        request.setModelCode(modelCode);
        request.setScope(scope);
        request.setTeamId(teamPid);
        request.setRoleId(rolePid);
        request.setViewType("table");
        request.setIsDefault(true);
        ViewConfig config = new ViewConfig();
        config.setDensity(density);
        config.setColumns(List.of(ColumnConfig.builder()
                .fieldCode("name").visible(true).width(width).order(0).build()));
        request.setViewConfig(config);
        savedViewService.create(request);
    }

    private Team team(String suffix) {
        Team team = new Team();
        team.setPid(UniqueIdGenerator.generate());
        team.setTenantId(testTenant.getId());
        team.setCode("overlay-team-" + suffix);
        team.setName("Overlay Team");
        team.setStatus("active");
        team.setDeletedFlag(false);
        team.setCreatedAt(Instant.now());
        team.setUpdatedAt(Instant.now());
        teamMapper.insert(team);
        return team;
    }

    private Role role(String suffix) {
        Role role = new Role();
        role.setPid(UniqueIdGenerator.generate());
        role.setTenantId(testTenant.getId());
        role.setCode("overlay-role-" + suffix);
        role.setName("Overlay Role");
        role.setType("CUSTOM");
        role.setScopeType("TENANT");
        role.setPriority(100);
        role.setStatus("active");
        role.setIsDefault(false);
        role.setIsSystem(false);
        role.setDeletedFlag(false);
        role.setCreatedAt(Instant.now());
        role.setUpdatedAt(Instant.now());
        roleMapper.insert(role);
        return role;
    }

    private void addCurrentMemberships(Team team, Role role) {
        TeamMember teamMember = new TeamMember();
        teamMember.setPid(UniqueIdGenerator.generate());
        teamMember.setTenantId(testTenant.getId());
        teamMember.setTeamId(team.getId());
        teamMember.setUserId(testUser.getId());
        teamMember.setRole("member");
        teamMember.setJoinedAt(Instant.now());
        teamMember.setCreatedAt(Instant.now());
        teamMember.setUpdatedAt(Instant.now());
        teamMemberMapper.insert(teamMember);

        UserRole userRole = new UserRole();
        userRole.setPid(UniqueIdGenerator.generate());
        userRole.setTenantId(testTenant.getId());
        userRole.setMemberId(testTenantMember.getId());
        userRole.setRoleId(role.getId());
        userRole.setStatus("active");
        userRole.setDeletedFlag(false);
        userRole.setCreatedAt(Instant.now());
        userRole.setUpdatedAt(Instant.now());
        userRoleMapper.insert(userRole);
    }
}
