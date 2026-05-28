package com.auraboot.framework.iot.spi.impl;

import com.auraboot.framework.iot.broker.EmqxAclSyncService;
import com.auraboot.framework.iot.security.IotCredentialEncryptionService;
import com.auraboot.framework.iot.security.IotJwtService;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.iot.BackgroundDeviceAccessor;
import com.auraboot.framework.plugin.extension.iot.BackgroundDeviceAccessor.DeviceView;
import com.auraboot.framework.plugin.extension.iot.BackgroundIotCredentialAccessor.CredentialType;
import com.auraboot.framework.plugin.extension.iot.BackgroundIotCredentialAccessor.IotCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IotCredentialAccessorImplTest {

    private DynamicDataService dds;
    private BackgroundDeviceAccessor deviceAccessor;
    private IotCredentialEncryptionService encryption;
    private IotJwtService jwt;
    private EmqxAclSyncService emqx;
    private IotCredentialAccessorImpl accessor;

    @BeforeEach
    void setUp() {
        dds = mock(DynamicDataService.class);
        deviceAccessor = mock(BackgroundDeviceAccessor.class);
        encryption = mock(IotCredentialEncryptionService.class);
        jwt = mock(IotJwtService.class);
        emqx = mock(EmqxAclSyncService.class);
        accessor = new IotCredentialAccessorImpl(dds, deviceAccessor, encryption, jwt, emqx);

        when(encryption.encrypt(anyLong(), anyString()))
                .thenAnswer(inv -> "ENC:" + inv.getArgument(1));
        when(jwt.issueDeviceJwt(any())).thenReturn("jwt-fake");
    }

    private DeviceView device(long tenantId, String code, String iotId) {
        return new DeviceView(iotId, code, "pk-air", tenantId, "ONLINE",
                "/sys/pk-air/" + code + "/#", Map.of(), null);
    }

    @Test
    void issueCredentials_persistsEncryptedSecret_andCallsEmqx() {
        when(deviceAccessor.lookupByCode(42L, "dev-1")).thenReturn(Optional.of(device(42L, "dev-1", "iot-1")));

        IotCredentials got = accessor.issueCredentials(42L, "dev-1", CredentialType.ACCESS_TOKEN);

        assertThat(got.type()).isEqualTo(CredentialType.ACCESS_TOKEN);
        assertThat(got.secret()).isNotBlank();
        assertThat(got.jwt()).isEqualTo("jwt-fake");
        assertThat(got.aclPatterns()).containsExactly("/sys/pk-air/dev-1/#");
        assertThat(got.expiresAt()).isNotNull();

        ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
        verify(dds).update(eq("iot_device"), eq("iot-1"), patch.capture());
        assertThat(patch.getValue()).containsEntry("iot_d_credentials_type", "ACCESS_TOKEN");
        assertThat((String) patch.getValue().get("iot_d_credentials_enc")).startsWith("ENC:");
        verify(emqx, times(1)).syncDeviceUser(eq(42L), eq("dev-1"), anyString(), eq(List.of("/sys/pk-air/dev-1/#")));
    }

    @Test
    void issueCredentials_skipsJwtMint_forMqttBasic() {
        when(deviceAccessor.lookupByCode(42L, "dev-1")).thenReturn(Optional.of(device(42L, "dev-1", "iot-1")));
        IotCredentials got = accessor.issueCredentials(42L, "dev-1", CredentialType.MQTT_BASIC);
        assertThat(got.jwt()).isNull();
        verify(jwt, never()).issueDeviceJwt(any());
    }

    @Test
    void issueCredentials_rejectsCrossTenant_andDoesNotWrite() {
        when(deviceAccessor.lookupByCode(42L, "dev-1"))
                .thenReturn(Optional.of(device(99L, "dev-1", "iot-1"))); // wrong tenant
        assertThatThrownBy(() -> accessor.issueCredentials(42L, "dev-1", CredentialType.ACCESS_TOKEN))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("device_tenant_mismatch");
        verify(dds, never()).update(anyString(), anyString(), any());
        verify(emqx, never()).syncDeviceUser(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void issueCredentials_throwsWhenDeviceMissing() {
        when(deviceAccessor.lookupByCode(42L, "ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> accessor.issueCredentials(42L, "ghost", CredentialType.ACCESS_TOKEN))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("device_not_found");
    }

    @Test
    void issueCredentials_brokerFailurePropagates_forTxRollback() {
        when(deviceAccessor.lookupByCode(42L, "dev-1")).thenReturn(Optional.of(device(42L, "dev-1", "iot-1")));
        doThrow(new MetaServiceException("iot.error.emqx_upsert_device_user status=500"))
                .when(emqx).syncDeviceUser(anyLong(), anyString(), anyString(), any());

        assertThatThrownBy(() -> accessor.issueCredentials(42L, "dev-1", CredentialType.ACCESS_TOKEN))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("emqx_upsert_device_user");
        // dds.update was invoked but the @Transactional boundary at the
        // outer level will roll it back (we can't observe that here, but
        // the propagation behavior is the contract).
    }

    @Test
    void revokeCredentials_marksDisable_andCallsEmqxRevoke() {
        when(deviceAccessor.lookupByCode(42L, "dev-1")).thenReturn(Optional.of(device(42L, "dev-1", "iot-1")));
        accessor.revokeCredentials(42L, "dev-1");
        ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
        verify(dds).update(eq("iot_device"), eq("iot-1"), patch.capture());
        assertThat(patch.getValue()).containsEntry("iot_d_status", "DISABLE");
        assertThat(patch.getValue()).containsEntry("iot_d_credentials_enc", null);
        verify(emqx).revokeDeviceUser(42L, "dev-1");
    }

    @Test
    void revokeCredentials_rejectsBlankOrZero() {
        assertThatThrownBy(() -> accessor.revokeCredentials(0L, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accessor.revokeCredentials(1L, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void syncAclToBroker_delegatesToBroker() {
        accessor.syncAclToBroker(42L);
        verify(emqx).syncTenantAcl(eq(42L), any());
    }

    @Test
    void resolveAclPatterns_usesStoredCommaSeparated_orDefault() {
        DeviceView withStored = new DeviceView("iot", "dev", "pk", 1L, "ONLINE",
                "/sys/pk/dev/up,/sys/pk/dev/down", Map.of(), null);
        assertThat(IotCredentialAccessorImpl.resolveAclPatterns(withStored))
                .containsExactly("/sys/pk/dev/up", "/sys/pk/dev/down");

        DeviceView empty = new DeviceView("iot", "dev", "pk", 1L, "ONLINE",
                null, Map.of(), null);
        assertThat(IotCredentialAccessorImpl.resolveAclPatterns(empty))
                .containsExactly("/sys/pk/dev/#");
    }
}
