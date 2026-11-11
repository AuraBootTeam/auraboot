package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.entity.RoleDataScope;
import com.auraboot.framework.permission.mapper.RoleDataScopeMapper;
import com.auraboot.framework.permission.service.DomainCommandAuthorization;
import com.auraboot.framework.permission.service.RoleAuthorizationWriteLock;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** SQL audiences must use the same existing role defaults as physical-row grant checks. */
class DataScopeServiceDomainDefaultTest {
    private final RoleDataScopeMapper scopeRows = mock(RoleDataScopeMapper.class);
    private final RoleMapper roles = mock(RoleMapper.class);
    private final UserRoleMapper memberships = mock(UserRoleMapper.class);
    private final PermissionSnapshotCache definitions = mock(PermissionSnapshotCache.class);
    private final DomainCommandAuthorization grants = mock(DomainCommandAuthorization.class);
    private final DataScopeServiceImpl service = new DataScopeServiceImpl(mock(RoleAuthorizationWriteLock.class),
            scopeRows, roles, memberships, mock(TenantMemberMapper.class));
    private static final String MODEL = "example_record";
    private static final String CODE = "bom.cap.read";
    private Role reader;

    @BeforeEach void context() {
        MetaContext.setContext(100L, 1L, "actor", "actor"); MetaContext.setMemberId(5L);
        ReflectionTestUtils.setField(service, "snapshots", definitions);
        ReflectionTestUtils.setField(service, "domains", grants);
        ReflectionTestUtils.setField(service, "metaModelService", mock(MetaModelService.class));
        when(memberships.findRoleIdsByMemberId(5L)).thenReturn(List.of(7L, 9L));
        when(scopeRows.findByRoleIdsAndResource(List.of(7L, 9L), MODEL, "read")).thenReturn(List.of());
        when(scopeRows.findByTenantAndRole(100L, 7L)).thenReturn(List.of());
        var definition = new Permission(); definition.setCode(CODE); definition.setResourceCode("bom");
        definition.setResourceType("operation"); definition.setAction("read");
        definition.setExtension(Map.of("domainModels", Map.of(MODEL, List.of("read"))));
        when(definitions.resourcePermissionCodes(100L, MODEL, "read")).thenReturn(List.of(CODE));
        when(definitions.resolvePermissionDefinition(100L, CODE)).thenReturn(definition);
        when(grants.grants(CODE)).thenReturn(List.of(grant(7L)));
        reader = new Role(); reader.setId(7L); reader.setDefaultDataScopeType("self");
        when(roles.findEffectiveGrantOwner(100L, 1L, 5L, 7L)).thenReturn(reader);
    }
    @AfterEach void clear() { MetaContext.clear(); }
    private RolePermissionMapper.BusinessGrantRow grant(long role) {
        var row = new RolePermissionMapper.BusinessGrantRow(); row.setRoleId(role);
        row.setPermissionCode(CODE); row.setGrantType("grant"); return row;
    }
    @Test void defaultSelfProducesTheSameOwnerPredicateAsTheRecordGrant() {
        var scope = service.resolveScope(5L, MODEL, "read");
        assertThat(scope.scopeType()).isEqualTo("self");
        assertThat(scope.ownerField()).isEqualTo("created_by");
        assertThat(scope.ownerValue()).isEqualTo(1L);
        verify(roles, never()).findEffectiveGrantOwner(100L, 1L, 5L, 9L);
    }
    @Test void anUnrelatedRoleDefaultCannotSupplyAllAccess() {
        var unrelated = new Role(); unrelated.setDefaultDataScopeType("all");
        when(roles.findEffectiveGrantOwner(100L, 1L, 5L, 9L)).thenReturn(unrelated);
        assertThat(service.resolveScope(5L, MODEL, "read").scopeType()).isEqualTo("self");
        verify(roles, never()).findEffectiveGrantOwner(100L, 1L, 5L, 9L);
    }
    @Test void noEffectiveFunctionalGrantOrExplicitDenyCannotManufactureAnAudience() {
        when(grants.grants(CODE)).thenReturn(List.of());
        assertThat(service.resolveScope(5L, MODEL, "read").scopeType()).isEqualTo("none");
        var denied = grant(7L); denied.setGrantType("deny");
        when(grants.grants(CODE)).thenReturn(List.of(denied));
        assertThat(service.resolveScope(5L, MODEL, "read").scopeType()).isEqualTo("none");
    }
    @Test void legacyAbsenceRemainsUnconfiguredAndDoesNotWriteScopeRows() {
        reader.setDefaultDataScopeType(null);
        assertThat(service.resolveScope(5L, MODEL, "read").scopeType()).isEqualTo("not_configured");
        verify(scopeRows, never()).insert(any(RoleDataScope.class));
    }
    @Test void existingExplicitNoneStillWinsOverTheRoleDefault() {
        var none = new RoleDataScope(); none.setScopeType("none"); none.setMergeStrategy("MAX");
        when(scopeRows.findByRoleIdsAndResource(List.of(7L, 9L), MODEL, "read")).thenReturn(List.of(none));
        assertThat(service.resolveScope(5L, MODEL, "read").scopeType()).isEqualTo("none");
        verifyNoInteractions(grants);
    }
    @Test void borrowedTechnicalEffectsDoNotChangeOrdinaryQueryDefaults() {
        var definition = new Permission(); definition.setCode(CODE); definition.setResourceCode("qo");
        definition.setResourceType("operation"); definition.setAction("write");
        definition.setExtension(Map.of("domainModels", Map.of("quote_root", List.of("read")),
                "commandEffects", Map.of(MODEL, List.of("read", "create"))));
        when(definitions.resolvePermissionDefinition(100L, CODE)).thenReturn(definition);
        MetaContext.runWithCommandPermitPlan("SELF", null, "quote_root", null, () ->
                MetaContext.runWithAuthorizedCommandCode("quote:create", () -> MetaContext.runWithCommandAuthority(CODE, () ->
                    assertThat(service.resolveScope(5L, MODEL, "read").scopeType()).isEqualTo("not_configured"))));
        verifyNoInteractions(grants);
    }
    @Test void anEffectiveAllGrantKeepsTheExistingMaxMergeMeaning() {
        var all = new Role(); all.setDefaultDataScopeType("all");
        when(roles.findEffectiveGrantOwner(100L, 1L, 5L, 9L)).thenReturn(all);
        when(scopeRows.findByTenantAndRole(100L, 9L)).thenReturn(List.of());
        when(grants.grants(CODE)).thenReturn(List.of(grant(7L), grant(9L)));
        assertThat(service.resolveScope(5L, MODEL, "read").scopeType()).isEqualTo("all");
    }
}
