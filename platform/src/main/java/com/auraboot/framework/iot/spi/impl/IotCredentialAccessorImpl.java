package com.auraboot.framework.iot.spi.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.iot.broker.EmqxAclSyncService;
import com.auraboot.framework.iot.security.IotCredentialEncryptionService;
import com.auraboot.framework.iot.security.IotJwtService;
import com.auraboot.framework.iot.security.IotJwtService.AclEntry;
import com.auraboot.framework.iot.security.IotJwtService.IotDeviceJwtClaims;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.iot.BackgroundDeviceAccessor;
import com.auraboot.framework.plugin.extension.iot.BackgroundDeviceAccessor.DeviceView;
import com.auraboot.framework.plugin.extension.iot.BackgroundIotCredentialAccessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default {@link BackgroundIotCredentialAccessor}: composes the device
 * accessor (row lookup) with {@link IotCredentialEncryptionService} (envelope
 * encryption), {@link IotJwtService} (device JWT minting) and
 * {@link EmqxAclSyncService} (broker reconciliation).
 *
 * <p><b>Transaction strategy:</b> the device-row write is the only durable
 * commit point. The broker sync is performed AFTER the local write but
 * inside the same {@code @Transactional} boundary — if the broker call
 * throws, the PG commit rolls back. This is a deliberate trade-off:
 * occasional re-issue attempts are cheaper than reconciling phantom rows.
 * Distributed-saga / outbox handoff is left for a future PR (see design doc
 * §6 future work).
 *
 * @since 2.6.0
 */
@Slf4j
@Service
public class IotCredentialAccessorImpl implements BackgroundIotCredentialAccessor {

    private static final long SYSTEM_USER_ID = 0L;
    private static final SecureRandom RNG = new SecureRandom();
    private static final int SECRET_BYTES = 32;

    private final DynamicDataService dynamicDataService;
    private final BackgroundDeviceAccessor deviceAccessor;
    private final IotCredentialEncryptionService encryption;
    private final IotJwtService jwtService;
    private final EmqxAclSyncService emqxSync;

    public IotCredentialAccessorImpl(DynamicDataService dynamicDataService,
                                     BackgroundDeviceAccessor deviceAccessor,
                                     IotCredentialEncryptionService encryption,
                                     IotJwtService jwtService,
                                     EmqxAclSyncService emqxSync) {
        this.dynamicDataService = dynamicDataService;
        this.deviceAccessor = deviceAccessor;
        this.encryption = encryption;
        this.jwtService = jwtService;
        this.emqxSync = emqxSync;
    }

    @Override
    @Transactional
    public IotCredentials issueCredentials(long tenantId, String deviceCode, CredentialType type) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be > 0");
        }
        if (deviceCode == null || deviceCode.isBlank()) {
            throw new IllegalArgumentException("deviceCode must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        Optional<DeviceView> existing = deviceAccessor.lookupByCode(tenantId, deviceCode);
        if (existing.isEmpty()) {
            throw new MetaServiceException("iot.error.device_not_found tenant=" + tenantId + " code=" + deviceCode);
        }
        DeviceView device = existing.get();
        if (device.tenantId() != tenantId) {
            throw new MetaServiceException("iot.error.device_tenant_mismatch");
        }

        // 1. Generate fresh material.
        String rawSecret = randomToken();
        List<String> aclPatterns = resolveAclPatterns(device);

        // 2. Mint JWT if applicable (the JWT carries acl claims; for non-JWT
        //    types we still issue the bearer/password and skip the token).
        String jwt = null;
        if (type == CredentialType.JWT || type == CredentialType.ACCESS_TOKEN) {
            List<AclEntry> aclClaims = new ArrayList<>();
            for (String t : aclPatterns) {
                aclClaims.add(new AclEntry("all", t));
            }
            jwt = jwtService.issueDeviceJwt(new IotDeviceJwtClaims(
                    tenantId,
                    device.productKey(),
                    device.deviceCode(),
                    device.iotId(),
                    aclClaims));
        }

        // 3. Encrypt the durable secret and persist on the device row.
        String encrypted = encryption.encrypt(tenantId, rawSecret);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("iot_d_credentials_type", type.name());
        patch.put("iot_d_credentials_enc", encrypted);
        patch.put("iot_d_acl_pattern", joinPatterns(aclPatterns));

        withTenant(tenantId, () -> {
            dynamicDataService.update(IotDeviceAccessorImpl.MODEL_CODE, device.iotId(), patch);
            return null;
        });

        // 4. Broker sync. Failure rolls back the row write (see class javadoc).
        try {
            emqxSync.syncDeviceUser(tenantId, device.deviceCode(), rawSecret, aclPatterns);
        } catch (MetaServiceException e) {
            log.warn("[iot-credential] broker sync failed tenant={} device={} — rolling back row write: {}",
                    tenantId, deviceCode, e.getMessage());
            throw e;
        }

        Instant expires = (type == CredentialType.ACCESS_TOKEN || type == CredentialType.JWT)
                ? Instant.now().plusSeconds(7L * 24 * 3600)
                : null;
        return new IotCredentials(type, rawSecret, jwt, aclPatterns, expires);
    }

    @Override
    @Transactional
    public void revokeCredentials(long tenantId, String deviceCode) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be > 0");
        }
        if (deviceCode == null || deviceCode.isBlank()) {
            throw new IllegalArgumentException("deviceCode must not be blank");
        }
        Optional<DeviceView> existing = deviceAccessor.lookupByCode(tenantId, deviceCode);
        if (existing.isEmpty()) {
            throw new MetaServiceException("iot.error.device_not_found tenant=" + tenantId + " code=" + deviceCode);
        }
        DeviceView device = existing.get();
        if (device.tenantId() != tenantId) {
            throw new MetaServiceException("iot.error.device_tenant_mismatch");
        }

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("iot_d_status", "DISABLE");
        patch.put("iot_d_credentials_enc", null);
        withTenant(tenantId, () -> {
            dynamicDataService.update(IotDeviceAccessorImpl.MODEL_CODE, device.iotId(), patch);
            return null;
        });

        emqxSync.revokeDeviceUser(tenantId, device.deviceCode());
    }

    @Override
    public void syncAclToBroker(long tenantId) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be > 0");
        }
        // The tenant-scope sweep is intentionally minimal here: the platform
        // surface for "list all devices" lives in the device accessor's
        // higher-level query, which is out of scope for the SPI surface. We
        // delegate to the broker layer's tenant-scope sync with an empty
        // principal list — concrete callers should fan out per device via
        // {@link #issueCredentials} or pre-resolve the list.
        emqxSync.syncTenantAcl(tenantId, List.of());
    }

    static List<String> resolveAclPatterns(DeviceView device) {
        String stored = device.aclPattern();
        if (stored != null && !stored.isBlank()) {
            List<String> out = new ArrayList<>();
            for (String s : stored.split("[,\\n;]")) {
                String t = s.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        // Default ACL pattern per design doc §6.
        return List.of(
                "/sys/" + device.productKey() + "/" + device.deviceCode() + "/#");
    }

    private static String joinPatterns(List<String> patterns) {
        return String.join(",", patterns);
    }

    private static String randomToken() {
        byte[] buf = new byte[SECRET_BYTES];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private <T> T withTenant(long tenantId, java.util.function.Supplier<T> work) {
        boolean had = MetaContext.exists();
        Long priorTenant = had ? MetaContext.getCurrentTenantId() : null;
        Long priorUser = had ? MetaContext.getCurrentUserId() : null;
        String priorUserPid = had ? MetaContext.getCurrentUserPid() : null;
        String priorUsername = had ? MetaContext.getCurrentUsername() : null;
        java.util.Set<Long> priorRoles = had ? MetaContext.getCurrentRoleIds() : java.util.Set.of();
        MetaContext.setContext(tenantId, SYSTEM_USER_ID, null, "system");
        try {
            return work.get();
        } finally {
            if (had) {
                MetaContext.setContext(priorTenant, priorUser, priorUserPid, priorUsername, priorRoles);
            } else {
                MetaContext.clear();
            }
        }
    }
}
