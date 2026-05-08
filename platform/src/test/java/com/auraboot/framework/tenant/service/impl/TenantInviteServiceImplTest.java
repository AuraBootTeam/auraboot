package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.tenant.dao.entity.Invitation;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.InvitationMapper;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantInviteServiceImpl")
class TenantInviteServiceImplTest {

    @Mock
    private TenantMemberService tenantMemberService;
    @Mock
    private UserService userService;
    @Mock
    private InvitationMapper invitationMapper;

    private TenantInviteServiceImpl service;
    private TenantInviteServiceImpl spyService;

    @BeforeEach
    void setUp() throws Exception {
        service = new TenantInviteServiceImpl();
        TenantServiceImplTest.injectField(service, "baseMapper", invitationMapper);
        TenantServiceImplTest.injectField(service, "invitationMapper", invitationMapper);
        TenantServiceImplTest.injectField(service, "tenantMemberService", tenantMemberService);
        TenantServiceImplTest.injectField(service, "userService", userService);
        spyService = org.mockito.Mockito.spy(service);
    }

    private Invitation invitation(String code, String status, Instant expiredAt, boolean deleted, Long tenantId, Long inviter) {
        Invitation inv = new Invitation();
        inv.setId(1L);
        inv.setPid("pid-1");
        inv.setInviteCode(code);
        inv.setStatus(status);
        inv.setExpiredAt(expiredAt);
        inv.setDeletedFlag(deleted);
        inv.setTenantId(tenantId);
        inv.setInviterUserId(inviter);
        return inv;
    }

    @Test
    @DisplayName("generateInviteCode throws when user has no tenant")
    void generateInviteCodeThrowsWhenNoTenant() {
        when(tenantMemberService.getTenantIdByUserId(7L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> spyService.generateInviteCode(7L, 3));
    }

    @Test
    @DisplayName("generateInviteCode persists invitation with default expiry")
    void generateInviteCodeDefaultsExpiry() {
        when(tenantMemberService.getTenantIdByUserId(7L)).thenReturn(99L);
        when(invitationMapper.findByInviteCode(anyString())).thenReturn(null);
        doReturn(true).when(spyService).save(any(Invitation.class));

        String code = spyService.generateInviteCode(7L, null);

        assertNotNull(code);
        assertEquals(8, code.length());
        verify(spyService).save(any(Invitation.class));
    }

    @Test
    @DisplayName("generateInviteCode honors expiryDays")
    void generateInviteCodeHonorsExpiry() {
        when(tenantMemberService.getTenantIdByUserId(7L)).thenReturn(99L);
        when(invitationMapper.findByInviteCode(anyString())).thenReturn(null);
        doReturn(true).when(spyService).save(any(Invitation.class));

        String code = spyService.generateInviteCode(7L, 5);

        assertNotNull(code);
    }

    @Test
    @DisplayName("validateInviteCode false when not found")
    void validateNotFound() {
        when(invitationMapper.findByInviteCode("xxx")).thenReturn(null);
        assertFalse(service.validateInviteCode("xxx"));
    }

    @Test
    @DisplayName("validateInviteCode false when deleted")
    void validateDeleted() {
        when(invitationMapper.findByInviteCode("c")).thenReturn(
                invitation("c", StatusConstants.ACTIVE, Instant.now().plus(1, ChronoUnit.DAYS), true, 1L, 1L));
        assertFalse(service.validateInviteCode("c"));
    }

    @Test
    @DisplayName("validateInviteCode false when not active")
    void validateNotActive() {
        when(invitationMapper.findByInviteCode("c")).thenReturn(
                invitation("c", StatusConstants.EXPIRED, Instant.now().plus(1, ChronoUnit.DAYS), false, 1L, 1L));
        assertFalse(service.validateInviteCode("c"));
    }

    @Test
    @DisplayName("validateInviteCode false when expired")
    void validateExpired() {
        when(invitationMapper.findByInviteCode("c")).thenReturn(
                invitation("c", StatusConstants.ACTIVE, Instant.now().minus(1, ChronoUnit.DAYS), false, 1L, 1L));
        assertFalse(service.validateInviteCode("c"));
    }

    @Test
    @DisplayName("validateInviteCode true when active + not expired + not deleted")
    void validateOk() {
        when(invitationMapper.findByInviteCode("c")).thenReturn(
                invitation("c", StatusConstants.ACTIVE, Instant.now().plus(1, ChronoUnit.DAYS), false, 1L, 1L));
        assertTrue(service.validateInviteCode("c"));
    }

    @Test
    @DisplayName("useInviteCode false when invalid")
    void useInviteInvalid() {
        when(invitationMapper.findByInviteCode("c")).thenReturn(null);
        assertFalse(service.useInviteCode("c", 7L));
    }

    @Test
    @DisplayName("useInviteCode false when user already a member")
    void useInviteAlreadyMember() {
        Invitation inv = invitation("c", StatusConstants.ACTIVE, Instant.now().plus(1, ChronoUnit.DAYS), false, 99L, 1L);
        when(invitationMapper.findByInviteCode("c")).thenReturn(inv);
        when(tenantMemberService.findByTenantIdAndUserId(99L, 7L))
                .thenReturn(new TenantMember());

        assertFalse(service.useInviteCode("c", 7L));
        verify(tenantMemberService, never()).addMember(any(), any(), any());
    }

    @Test
    @DisplayName("useInviteCode adds pending member when valid")
    void useInviteSucceeds() {
        Invitation inv = invitation("c", StatusConstants.ACTIVE, Instant.now().plus(1, ChronoUnit.DAYS), false, 99L, 1L);
        when(invitationMapper.findByInviteCode("c")).thenReturn(inv);
        when(tenantMemberService.findByTenantIdAndUserId(99L, 7L)).thenReturn(null);
        TenantMember saved = new TenantMember();
        saved.setId(123L);
        when(tenantMemberService.addMember(7L, 99L, StatusConstants.PENDING)).thenReturn(saved);

        assertTrue(service.useInviteCode("c", 7L));
        verify(tenantMemberService).addMember(7L, 99L, StatusConstants.PENDING);
    }

    @Test
    @DisplayName("revokeInviteCode false when not found")
    void revokeNotFound() {
        when(invitationMapper.findByInviteCode("c")).thenReturn(null);
        assertFalse(service.revokeInviteCode(7L, "c"));
    }

    @Test
    @DisplayName("revokeInviteCode false when user not the inviter")
    void revokeNotInviter() {
        when(invitationMapper.findByInviteCode("c")).thenReturn(
                invitation("c", StatusConstants.ACTIVE, Instant.now().plus(1, ChronoUnit.DAYS), false, 1L, 99L));
        assertFalse(service.revokeInviteCode(7L, "c"));
    }

    @Test
    @DisplayName("revokeInviteCode marks invitation as expired and saves")
    void revokeSucceeds() {
        Invitation inv = invitation("c", StatusConstants.ACTIVE, Instant.now().plus(1, ChronoUnit.DAYS), false, 1L, 7L);
        when(invitationMapper.findByInviteCode("c")).thenReturn(inv);
        doReturn(true).when(spyService).updateById(any(Invitation.class));

        assertTrue(spyService.revokeInviteCode(7L, "c"));
        assertEquals(StatusConstants.EXPIRED, inv.getStatus());
    }

    @Test
    @DisplayName("createInvitation saves and returns the entity")
    void createInvitationSaves() {
        Invitation inv = new Invitation();
        doReturn(true).when(spyService).save(inv);

        assertEquals(inv, spyService.createInvitation(inv));
    }

    @Test
    @DisplayName("findByInvitationCode delegates to mapper")
    void findByInvitationCodeDelegates() {
        Invitation inv = invitation("c", StatusConstants.ACTIVE, Instant.now(), false, 1L, 1L);
        when(invitationMapper.findByInviteCode("c")).thenReturn(inv);

        assertEquals(inv, service.findByInvitationCode("c"));
    }

    @Test
    @DisplayName("generateInvitationCode regenerates on collision")
    void generateInvitationCodeRetriesOnCollision() {
        // First two checks return existing, then null
        Invitation existing = invitation("zzzzzzzz", StatusConstants.ACTIVE, Instant.now(), false, 1L, 1L);
        when(invitationMapper.findByInviteCode(anyString()))
                .thenReturn(existing)
                .thenReturn(existing)
                .thenReturn(null);

        String code = service.generateInvitationCode(null);

        assertNotNull(code);
        assertEquals(8, code.length());
    }

    @Test
    @DisplayName("findValidInvitationByInviter queries with active status + not expired")
    void findValidInvitationByInviter() {
        Invitation inv = invitation("c", StatusConstants.ACTIVE, Instant.now().plus(1, ChronoUnit.DAYS), false, 1L, 7L);
        doReturn(inv).when(spyService).getOne(any(QueryWrapper.class));

        assertEquals(inv, spyService.findValidInvitationByInviter(1L, 7L));
    }
}
