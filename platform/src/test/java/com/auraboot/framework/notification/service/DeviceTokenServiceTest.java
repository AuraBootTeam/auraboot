package com.auraboot.framework.notification.service;

import com.auraboot.framework.notification.mapper.PushDeviceTokenMapper;
import com.auraboot.framework.notification.model.PushDeviceToken;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceTokenServiceTest {

    @Mock
    PushDeviceTokenMapper mapper;

    @Test
    void registerToken_createsNewWhenNoExisting() {
        DeviceTokenService svc = new DeviceTokenService(mapper);
        when(mapper.findByPushTokenIncludeDeleted(1L, "tok")).thenReturn(null);

        PushDeviceToken result = svc.registerToken(1L, 2L, "ios", "tok", "dev",
                "fcm", "1.0", "iOS 17");

        assertThat(result.getTenantId()).isEqualTo(1L);
        assertThat(result.getUserId()).isEqualTo(2L);
        assertThat(result.getPushToken()).isEqualTo("tok");
        assertThat(result.getTokenType()).isEqualTo("fcm");
        assertThat(result.getIsValid()).isTrue();
        assertThat(result.getDeletedFlag()).isFalse();
        verify(mapper).insert(any(PushDeviceToken.class));
        verify(mapper, never()).updateById(any(PushDeviceToken.class));
    }

    @Test
    void registerToken_defaultsTokenTypeToApns() {
        DeviceTokenService svc = new DeviceTokenService(mapper);
        when(mapper.findByPushTokenIncludeDeleted(1L, "tok")).thenReturn(null);
        PushDeviceToken result = svc.registerToken(1L, 2L, "ios", "tok", "dev",
                null, "1.0", "iOS");
        assertThat(result.getTokenType()).isEqualTo("apns");
    }

    @Test
    void registerToken_updatesExisting() {
        DeviceTokenService svc = new DeviceTokenService(mapper);
        PushDeviceToken existing = new PushDeviceToken();
        existing.setId(123L);
        existing.setIsValid(false);
        when(mapper.findByPushTokenIncludeDeleted(1L, "tok")).thenReturn(existing);

        PushDeviceToken result = svc.registerToken(1L, 2L, "ios", "tok", "dev",
                "fcm", "1.0", "iOS");

        assertThat(result).isSameAs(existing);
        assertThat(result.getUserId()).isEqualTo(2L);
        assertThat(result.getIsValid()).isTrue();
        assertThat(result.getDeletedFlag()).isFalse();
        verify(mapper).updateById(existing);
        verify(mapper, never()).insert(any(PushDeviceToken.class));
    }

    @Test
    void unregisterToken_invokesUpdateWithSoftDelete() {
        DeviceTokenService svc = new DeviceTokenService(mapper);
        when(mapper.update(isNull(), any())).thenReturn(1);
        svc.unregisterToken(1L, 2L, "tok");
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        assertThat(captor.getValue()).isNotNull();
    }

    @Test
    void getValidTokens_delegatesToMapper() {
        DeviceTokenService svc = new DeviceTokenService(mapper);
        PushDeviceToken t = new PushDeviceToken();
        when(mapper.findValidTokensByUserId(1L, 2L)).thenReturn(List.of(t));
        assertThat(svc.getValidTokens(1L, 2L)).containsExactly(t);
    }

    @Test
    void invalidateToken_delegatesToMapper() {
        DeviceTokenService svc = new DeviceTokenService(mapper);
        when(mapper.invalidateToken(99L)).thenReturn(1);
        svc.invalidateToken(99L);
        verify(mapper).invalidateToken(99L);
    }
}
