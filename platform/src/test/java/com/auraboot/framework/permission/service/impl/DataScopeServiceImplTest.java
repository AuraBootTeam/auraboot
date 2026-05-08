package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.organization.service.OrganizationService;
import com.auraboot.framework.permission.engine.model.DataScopeCondition;
import com.auraboot.framework.permission.entity.RoleDataScope;
import com.auraboot.framework.permission.mapper.RoleDataScopeMapper;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DataScopeServiceImpl}.
 *
 * <p>Uses reflection to populate the @Lazy-injected fields that Mockito's
 * {@code @InjectMocks} normally handles via field injection.
 */
@ExtendWith(MockitoExtension.class)
class DataScopeServiceImplTest {

    @Mock
    private RoleDataScopeMapper roleDataScopeMapper;

    @Mock
    private UserRoleMapper userRoleMapper;

    @Mock
    private TenantMemberMapper tenantMemberMapper;

    @Mock
    private OrganizationService organizationService;

    @Mock
    private MetaModelService metaModelService;

    @InjectMocks
    private DataScopeServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        MetaContext.setContext(100L, 1L, "u-pid", "tester");
        // The @Lazy fields are package-private @Autowired by Spring; @InjectMocks
        // populates them by name when types match. Ensure both are set explicitly.
        var orgField = DataScopeServiceImpl.class.getDeclaredField("organizationService");
        orgField.setAccessible(true);
        orgField.set(service, organizationService);
        var metaField = DataScopeServiceImpl.class.getDeclaredField("metaModelService");
        metaField.setAccessible(true);
        metaField.set(service, metaModelService);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void resolveScopeReturnsNotConfiguredWhenNoRoles() {
        when(userRoleMapper.findRoleIdsByMemberId(5L)).thenReturn(List.of());

        DataScopeCondition condition = service.resolveScope(5L, "model.user", "read");

        assertThat(condition.scopeType()).isEqualTo("not_configured");
    }

    @Test
    void resolveScopeReturnsNotConfiguredWhenNoScopeRows() {
        when(userRoleMapper.findRoleIdsByMemberId(5L)).thenReturn(List.of(7L));
        when(roleDataScopeMapper.findByRoleIdsAndResource(List.of(7L), "model.user", "read")).thenReturn(List.of());

        assertThat(service.resolveScope(5L, "model.user", "read").scopeType()).isEqualTo("not_configured");
    }

    @Test
    void resolveScopeReturnsAllForAllScope() {
        when(userRoleMapper.findRoleIdsByMemberId(5L)).thenReturn(List.of(7L));
        RoleDataScope scope = new RoleDataScope();
        scope.setScopeType("all");
        when(roleDataScopeMapper.findByRoleIdsAndResource(List.of(7L), "model.user", "read")).thenReturn(List.of(scope));

        assertThat(service.resolveScope(5L, "model.user", "read").scopeType()).isEqualTo("all");
    }

    @Test
    void resolveScopeReturnsNoneForNoneScope() {
        when(userRoleMapper.findRoleIdsByMemberId(5L)).thenReturn(List.of(7L));
        RoleDataScope scope = new RoleDataScope();
        scope.setScopeType("none");
        when(roleDataScopeMapper.findByRoleIdsAndResource(List.of(7L), "model.user", "read")).thenReturn(List.of(scope));

        assertThat(service.resolveScope(5L, "model.user", "read").scopeType()).isEqualTo("none");
    }

    @Test
    void resolveScopeMergesMaxByDefault() {
        when(userRoleMapper.findRoleIdsByMemberId(5L)).thenReturn(List.of(7L, 8L));
        RoleDataScope a = new RoleDataScope();
        a.setScopeType("self");
        a.setMergeStrategy("MAX");
        RoleDataScope b = new RoleDataScope();
        b.setScopeType("all");
        b.setMergeStrategy("MAX");
        when(roleDataScopeMapper.findByRoleIdsAndResource(any(), anyString(), anyString())).thenReturn(List.of(a, b));

        // MAX → all (more permissive)
        assertThat(service.resolveScope(5L, "model.user", "read").scopeType()).isEqualTo("all");
    }

    @Test
    void resolveScopeMergesMinWhenAnyRoleSetsMin() {
        when(userRoleMapper.findRoleIdsByMemberId(5L)).thenReturn(List.of(7L, 8L));
        RoleDataScope a = new RoleDataScope();
        a.setScopeType("self");
        a.setMergeStrategy("MIN");
        RoleDataScope b = new RoleDataScope();
        b.setScopeType("all");
        b.setMergeStrategy("MAX");
        when(roleDataScopeMapper.findByRoleIdsAndResource(any(), anyString(), anyString())).thenReturn(List.of(a, b));

        // MIN → self (least permissive)
        assertThat(service.resolveScope(5L, "model.user", "read").scopeType()).isEqualTo("self");
    }

    @Test
    void resolveScopeBuildsSelfConditionWithDefaultOwnerField() {
        when(userRoleMapper.findRoleIdsByMemberId(5L)).thenReturn(List.of(7L));
        RoleDataScope scope = new RoleDataScope();
        scope.setScopeType("self");
        when(roleDataScopeMapper.findByRoleIdsAndResource(any(), anyString(), anyString())).thenReturn(List.of(scope));
        // No model config — null path uses default
        when(metaModelService.getModelDefinition("model.user")).thenReturn(Optional.empty());

        DataScopeCondition c = service.resolveScope(5L, "model.user", "read");

        assertThat(c.scopeType()).isEqualTo("self");
        assertThat(c.ownerField()).isEqualTo("created_by");
        assertThat(c.ownerValue()).isEqualTo(1L); // current userId from MetaContext
    }

    @Test
    void resolveScopeUsesCustomOwnerFieldFromExtension() {
        when(userRoleMapper.findRoleIdsByMemberId(5L)).thenReturn(List.of(7L));
        RoleDataScope scope = new RoleDataScope();
        scope.setScopeType("self");
        when(roleDataScopeMapper.findByRoleIdsAndResource(any(), anyString(), anyString())).thenReturn(List.of(scope));

        when(metaModelService.getModelDefinition("model.user")).thenReturn(Optional.of(ModelDefinition.builder().build()));
        MetaModelDTO modelDto = MetaModelDTO.builder()
                .extension(Map.of("dataScope", Map.of("ownerField", "owner_user_id")))
                .build();
        when(metaModelService.findByCode("model.user")).thenReturn(modelDto);

        DataScopeCondition c = service.resolveScope(5L, "model.user", "read");

        assertThat(c.ownerField()).isEqualTo("owner_user_id");
    }

    @Test
    void resolveScopeFallsBackToSelfWhenMemberMissing() {
        when(userRoleMapper.findRoleIdsByMemberId(5L)).thenReturn(List.of(7L));
        RoleDataScope scope = new RoleDataScope();
        scope.setScopeType("dept");
        when(roleDataScopeMapper.findByRoleIdsAndResource(any(), anyString(), anyString())).thenReturn(List.of(scope));
        when(tenantMemberMapper.selectById(5L)).thenReturn(null);
        when(metaModelService.getModelDefinition("model.user")).thenReturn(Optional.empty());

        DataScopeCondition c = service.resolveScope(5L, "model.user", "read");

        assertThat(c.scopeType()).isEqualTo("self");
    }

    @Test
    void resolveScopeFallsBackToSelfWhenEmployeeMissing() {
        when(userRoleMapper.findRoleIdsByMemberId(5L)).thenReturn(List.of(7L));
        RoleDataScope scope = new RoleDataScope();
        scope.setScopeType("dept");
        when(roleDataScopeMapper.findByRoleIdsAndResource(any(), anyString(), anyString())).thenReturn(List.of(scope));
        TenantMember m = new TenantMember();
        m.setId(5L);
        m.setPid("member-pid");
        when(tenantMemberMapper.selectById(5L)).thenReturn(m);
        when(organizationService.getEmployeeByMemberPid("member-pid")).thenReturn(null);
        lenient().when(metaModelService.getModelDefinition("model.user")).thenReturn(Optional.empty());

        assertThat(service.resolveScope(5L, "model.user", "read").scopeType()).isEqualTo("self");
    }

    @Test
    void resolveScopeBuildsDeptConditionWithMembersDept() {
        when(userRoleMapper.findRoleIdsByMemberId(5L)).thenReturn(List.of(7L));
        RoleDataScope scope = new RoleDataScope();
        scope.setScopeType("dept");
        when(roleDataScopeMapper.findByRoleIdsAndResource(any(), anyString(), anyString())).thenReturn(List.of(scope));
        TenantMember m = new TenantMember();
        m.setId(5L);
        m.setPid("member-pid");
        when(tenantMemberMapper.selectById(5L)).thenReturn(m);
        when(organizationService.getEmployeeByMemberPid("member-pid"))
                .thenReturn(Map.of("org_emp_dept_id", "dept-1"));
        when(metaModelService.getModelDefinition("model.user")).thenReturn(Optional.empty());

        DataScopeCondition c = service.resolveScope(5L, "model.user", "read");

        assertThat(c.scopeType()).isEqualTo("dept");
        assertThat(c.deptPids()).containsExactly("dept-1");
    }

    @Test
    void resolveScopeBuildsDeptAndSubConditionUsingOrg() {
        when(userRoleMapper.findRoleIdsByMemberId(5L)).thenReturn(List.of(7L));
        RoleDataScope scope = new RoleDataScope();
        scope.setScopeType("dept_and_sub");
        when(roleDataScopeMapper.findByRoleIdsAndResource(any(), anyString(), anyString())).thenReturn(List.of(scope));
        TenantMember m = new TenantMember();
        m.setId(5L);
        m.setPid("member-pid");
        when(tenantMemberMapper.selectById(5L)).thenReturn(m);
        when(organizationService.getEmployeeByMemberPid("member-pid"))
                .thenReturn(Map.of("org_emp_dept_id", "dept-1"));
        when(organizationService.getDeptAndSubPids("dept-1")).thenReturn(List.of("dept-1", "dept-1.1"));
        when(metaModelService.getModelDefinition("model.user")).thenReturn(Optional.empty());

        DataScopeCondition c = service.resolveScope(5L, "model.user", "read");

        assertThat(c.scopeType()).isEqualTo("dept_and_sub");
        assertThat(c.deptPids()).containsExactlyInAnyOrder("dept-1", "dept-1.1");
    }

    @Test
    void setScopeUpdatesExistingRow() {
        RoleDataScope existing = new RoleDataScope();
        existing.setId(900L);
        existing.setScopeType("self");
        when(roleDataScopeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        service.setScope(100L, 7L, "model.user", "read", "all", "MAX");

        ArgumentCaptor<RoleDataScope> captor = ArgumentCaptor.forClass(RoleDataScope.class);
        verify(roleDataScopeMapper).updateById(captor.capture());
        assertThat(captor.getValue().getScopeType()).isEqualTo("all");
        assertThat(captor.getValue().getMergeStrategy()).isEqualTo("MAX");
    }

    @Test
    void setScopeInsertsWhenNoRowExists() {
        when(roleDataScopeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.setScope(100L, 7L, "model.user", "read", "self", null);

        ArgumentCaptor<RoleDataScope> captor = ArgumentCaptor.forClass(RoleDataScope.class);
        verify(roleDataScopeMapper).insert(captor.capture());
        assertThat(captor.getValue().getScopeType()).isEqualTo("self");
        assertThat(captor.getValue().getMergeStrategy()).isEqualTo("MAX"); // default
        assertThat(captor.getValue().getPid()).isNotBlank();
    }

    @Test
    void removeScopeDelegatesToMapper() {
        service.removeScope(100L, 7L, "model.user", "read");

        verify(roleDataScopeMapper).delete(any(LambdaQueryWrapper.class));
    }

    @Test
    void getScopesByRoleDelegatesToMapper() {
        when(roleDataScopeMapper.findByTenantAndRole(100L, 7L)).thenReturn(List.of(new RoleDataScope()));

        assertThat(service.getScopesByRole(100L, 7L)).hasSize(1);
    }
}
