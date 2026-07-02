package com.auraboot.framework.rbac.service.impl;

import com.auraboot.framework.permission.event.UserRoleChangedEvent;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Member↔role mutations must publish {@link UserRoleChangedEvent} so the member's
 * user-permissions cache is evicted AFTER COMMIT. Before this, adding/removing a role
 * from a member silently took up to the cache TTL (30min) to become effective — in the
 * revoke direction a de-provisioned member kept their old permissions
 * (DDR-2026-06-29 §12; B-deployment incident required backend restarts after grants).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserRoleServiceImpl cache-eviction events")
class UserRoleServiceImplEvictEventTest {

    private static final Long MEMBER_ID = 11L;
    private static final Long USER_ID = 99L;
    private static final Long ROLE_ID = 42L;
    private static final Long TENANT_ID = 1L;

    @Mock private UserRoleMapper userRoleMapper;
    @Mock private RoleMapper roleMapper;
    @Mock private TenantMemberMapper tenantMemberMapper;
    @Mock private ApplicationEventPublisher eventPublisher;

    private UserRoleServiceImpl spyService;

    @BeforeEach
    void setUp() throws Exception {
        UserRoleServiceImpl service = new UserRoleServiceImpl();
        injectField(service, "baseMapper", userRoleMapper);
        injectField(service, "userRoleMapper", userRoleMapper);
        injectField(service, "roleMapper", roleMapper);
        injectField(service, "tenantMemberMapper", tenantMemberMapper);
        injectField(service, "eventPublisher", eventPublisher);
        spyService = spy(service);
    }

    static void injectField(Object target, String name, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private TenantMember memberWithUser() {
        TenantMember member = new TenantMember();
        member.setId(MEMBER_ID);
        member.setUserId(USER_ID);
        return member;
    }

    private UserRoleChangedEvent publishedEvent() {
        ArgumentCaptor<org.springframework.context.ApplicationEvent> captor =
            ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return (UserRoleChangedEvent) captor.getValue();
    }

    @Test
    @DisplayName("assignRolesToMember publishes UserRoleChangedEvent with the member's userId")
    void assignPublishes() {
        when(userRoleMapper.findByMemberIdAndRoleIdAndTenantId(MEMBER_ID, ROLE_ID, TENANT_ID))
            .thenReturn(null);
        doReturn(true).when(spyService).saveBatch(anyList());
        when(tenantMemberMapper.selectById(MEMBER_ID)).thenReturn(memberWithUser());

        assertTrue(spyService.assignRolesToMember(MEMBER_ID, List.of(ROLE_ID), TENANT_ID, 1L));

        assertEquals(USER_ID, publishedEvent().getUserId());
    }

    @Test
    @DisplayName("removeMemberRole publishes UserRoleChangedEvent (revoke must evict)")
    void removeMemberRolePublishes() {
        doReturn(true).when(spyService).remove(any());
        when(tenantMemberMapper.selectById(MEMBER_ID)).thenReturn(memberWithUser());

        assertTrue(spyService.removeMemberRole(MEMBER_ID, ROLE_ID, TENANT_ID));

        UserRoleChangedEvent event = publishedEvent();
        assertEquals(USER_ID, event.getUserId());
        assertEquals(ROLE_ID, event.getRoleId());
    }

    @Test
    @DisplayName("removeAllRolesFromMemberInTenant publishes UserRoleChangedEvent")
    void removeAllPublishes() {
        when(userRoleMapper.deleteByMemberIdAndTenantId(MEMBER_ID, TENANT_ID)).thenReturn(2);
        when(tenantMemberMapper.selectById(MEMBER_ID)).thenReturn(memberWithUser());

        assertTrue(spyService.removeAllRolesFromMemberInTenant(MEMBER_ID, TENANT_ID));

        assertEquals(USER_ID, publishedEvent().getUserId());
    }

    @Test
    @DisplayName("batchDeactivateUserRoles publishes per affected member")
    void batchDeactivatePublishes() {
        UserRole row = new UserRole();
        row.setMemberId(MEMBER_ID);
        row.setRoleId(ROLE_ID);
        doReturn(true).when(spyService).update(any());
        doReturn(List.of(row)).when(spyService).listByIds(anyList());
        when(tenantMemberMapper.selectById(MEMBER_ID)).thenReturn(memberWithUser());

        assertEquals(1, spyService.batchDeactivateUserRoles(List.of(5L)));

        UserRoleChangedEvent event = publishedEvent();
        assertEquals(USER_ID, event.getUserId());
        assertEquals(ROLE_ID, event.getRoleId());
    }

    @Test
    @DisplayName("member without a linked user publishes nothing (no NPE, no bogus key)")
    void memberWithoutUserSkipsPublish() {
        TenantMember member = new TenantMember();
        member.setId(MEMBER_ID);
        doReturn(true).when(spyService).remove(any());
        when(tenantMemberMapper.selectById(MEMBER_ID)).thenReturn(member);

        assertTrue(spyService.removeMemberRole(MEMBER_ID, ROLE_ID, TENANT_ID));

        verify(eventPublisher, never()).publishEvent(any(org.springframework.context.ApplicationEvent.class));
    }
}
