package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.entity.UserDeactivation;
import com.auraboot.framework.auth.mapper.UserDeactivationMapper;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.service.SocialUnlinkService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserDeactivationServiceImpl")
class UserDeactivationServiceImplTest {

    @Mock private UserDeactivationMapper deactivationMapper;
    @Mock private UserService userService;
    @Mock private SessionManagementService sessionManagementService;
    @Mock private SocialUnlinkService socialUnlinkService;

    private UserDeactivationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserDeactivationServiceImpl(deactivationMapper, userService, sessionManagementService);
    }

    private User user(Long id, boolean enabled) {
        User u = new User();
        u.setId(id);
        u.setPid("u-" + id);
        u.setEmail("a@b.com");
        u.setEnabled(enabled);
        return u;
    }

    @Test
    @DisplayName("requestDeactivation throws when active deactivation exists")
    void requestActiveExists() {
        when(deactivationMapper.findActiveByUserId(1L)).thenReturn(new UserDeactivation());
        assertThrows(BusinessException.class, () -> service.requestDeactivation(1L, "reason", "snap"));
    }

    @Test
    @DisplayName("requestDeactivation throws when user missing")
    void requestUserMissing() {
        when(deactivationMapper.findActiveByUserId(1L)).thenReturn(null);
        when(userService.findByUserId(1L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.requestDeactivation(1L, "reason", "snap"));
    }

    @Test
    @DisplayName("requestDeactivation throws when user disabled")
    void requestUserDisabled() {
        when(deactivationMapper.findActiveByUserId(1L)).thenReturn(null);
        when(userService.findByUserId(1L)).thenReturn(user(1L, false));
        assertThrows(BusinessException.class, () -> service.requestDeactivation(1L, "reason", "snap"));
    }

    @Test
    @DisplayName("requestDeactivation creates record and updates user")
    void requestHappy() {
        User u = user(1L, true);
        when(deactivationMapper.findActiveByUserId(1L)).thenReturn(null);
        when(userService.findByUserId(1L)).thenReturn(u);

        UserDeactivation d = service.requestDeactivation(1L, "reason", "snap");
        assertEquals("cooling_off", d.getStatus());
        assertNotNull(d.getRequestedAt());
        assertNotNull(d.getCoolingOffUntil());
        assertEquals("cooling_off", u.getDeactivationStatus());
        verify(deactivationMapper).insert(any(UserDeactivation.class));
        verify(userService).update(u);
    }

    @Test
    @DisplayName("cancelDeactivation throws when none active")
    void cancelNone() {
        when(deactivationMapper.findActiveByUserId(1L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.cancelDeactivation(1L));
    }

    @Test
    @DisplayName("cancelDeactivation throws when DB cancel fails")
    void cancelDbFails() {
        when(deactivationMapper.findActiveByUserId(1L)).thenReturn(new UserDeactivation());
        when(deactivationMapper.cancelByUserId(1L)).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.cancelDeactivation(1L));
    }

    @Test
    @DisplayName("cancelDeactivation clears user status when user found")
    void cancelHappy() {
        when(deactivationMapper.findActiveByUserId(1L)).thenReturn(new UserDeactivation());
        when(deactivationMapper.cancelByUserId(1L)).thenReturn(1);
        User u = user(1L, true);
        u.setDeactivationStatus("cooling_off");
        when(userService.findByUserId(1L)).thenReturn(u);

        service.cancelDeactivation(1L);
        org.junit.jupiter.api.Assertions.assertNull(u.getDeactivationStatus());
        verify(userService).update(u);
    }

    @Test
    @DisplayName("getDeactivationStatus delegates to mapper")
    void getStatusDelegates() {
        UserDeactivation d = new UserDeactivation();
        when(deactivationMapper.findActiveByUserId(1L)).thenReturn(d);
        assertEquals(d, service.getDeactivationStatus(1L));
    }

    @Test
    @DisplayName("processExpiredDeactivations is silent when none expired")
    void processNoneExpired() {
        when(deactivationMapper.findExpiredCoolingOff()).thenReturn(List.of());
        service.processExpiredDeactivations();
        verify(userService, never()).update(any(User.class));
    }

    @Test
    @DisplayName("processExpiredDeactivations anonymizes user, revokes sessions, completes record")
    void processExpiredHappy() {
        User u = user(1L, true);
        u.setSecurityVersion(1);
        UserDeactivation d = new UserDeactivation();
        d.setUserId(1L);
        d.setUserEmail("a@b.com");

        when(deactivationMapper.findExpiredCoolingOff()).thenReturn(List.of(d));
        when(userService.findByUserId(1L)).thenReturn(u);

        service.processExpiredDeactivations();
        assertFalse(u.isEnabled());
        assertEquals(2, u.getSecurityVersion());
        assertEquals("deactivated", u.getDeactivationStatus());
        verify(sessionManagementService).revokeAllSessions(1L);
        verify(deactivationMapper).updateById(d);
    }

    @Test
    @DisplayName("processExpiredDeactivations completes record when user gone")
    void processExpiredUserGone() {
        UserDeactivation d = new UserDeactivation();
        d.setUserId(1L);
        when(deactivationMapper.findExpiredCoolingOff()).thenReturn(List.of(d));
        when(userService.findByUserId(1L)).thenReturn(null);

        service.processExpiredDeactivations();
        verify(deactivationMapper).updateById(d);
        verify(sessionManagementService, never()).revokeAllSessions(any());
    }

    @Test
    @DisplayName("processExpiredDeactivations calls SocialUnlinkService when bean present")
    void processExpiredWithSocialUnlink() {
        ReflectionTestUtils.setField(service, "socialUnlinkService", socialUnlinkService);
        User u = user(1L, true);
        UserDeactivation d = new UserDeactivation();
        d.setUserId(1L);

        when(deactivationMapper.findExpiredCoolingOff()).thenReturn(List.of(d));
        when(userService.findByUserId(1L)).thenReturn(u);

        service.processExpiredDeactivations();
        verify(socialUnlinkService, times(1)).unlinkAllByUserId(1L);
    }

    @Test
    @DisplayName("processExpiredDeactivations continues when one anonymize fails")
    void processExpiredContinuesOnError() {
        UserDeactivation d1 = new UserDeactivation(); d1.setUserId(1L);
        UserDeactivation d2 = new UserDeactivation(); d2.setUserId(2L);
        when(deactivationMapper.findExpiredCoolingOff()).thenReturn(List.of(d1, d2));
        when(userService.findByUserId(1L)).thenThrow(new RuntimeException("db blew up"));
        when(userService.findByUserId(2L)).thenReturn(user(2L, true));

        service.processExpiredDeactivations();
        verify(deactivationMapper).updateById(d2);
    }
}
