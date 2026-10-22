package com.auraboot.framework.notification;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.notification.model.PushDeviceToken;
import com.auraboot.framework.notification.service.DeviceTokenService;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for DeviceTokenService.
 * Tests token registration (create + upsert), unregistration, validation, and invalidation.
 *
 * @since 6.4.0
 */
class DeviceTokenServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DeviceTokenService deviceTokenService;

    @Test
    @Order(1)
    void registerToken_createsNewToken() {
        String uniqueToken = "test-push-token-" + System.currentTimeMillis();
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();

        PushDeviceToken result = deviceTokenService.registerToken(
                tenantId, userId, "ios", uniqueToken,
                "device-001", "apns", "1.0.0", "18.0");

        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        assertThat(result.getTenantId()).isEqualTo(tenantId);
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getPlatform()).isEqualTo("ios");
        assertThat(result.getPushToken()).isEqualTo(uniqueToken);
        assertThat(result.getDeviceId()).isEqualTo("device-001");
        assertThat(result.getTokenType()).isEqualTo("apns");
        assertThat(result.getAppVersion()).isEqualTo("1.0.0");
        assertThat(result.getOsVersion()).isEqualTo("18.0");
        assertThat(result.getIsValid()).isTrue();
        assertThat(result.getLastUsedAt()).isNotNull();
        assertThat(result.getDeletedFlag()).isFalse();
    }

    @Test
    @Order(2)
    void registerToken_upsertExistingToken() {
        String uniqueToken = "test-upsert-token-" + System.currentTimeMillis();
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();

        // First registration
        PushDeviceToken first = deviceTokenService.registerToken(
                tenantId, userId, "ios", uniqueToken,
                "device-001", "apns", "1.0.0", "17.0");
        assertThat(first).isNotNull();
        Long firstId = first.getId();

        // Second registration with same push_token — should update, not create new
        PushDeviceToken second = deviceTokenService.registerToken(
                tenantId, userId, "ios", uniqueToken,
                "device-002", "apns", "2.0.0", "18.0");

        assertThat(second).isNotNull();
        assertThat(second.getId()).isEqualTo(firstId);
        assertThat(second.getDeviceId()).isEqualTo("device-002");
        assertThat(second.getAppVersion()).isEqualTo("2.0.0");
        assertThat(second.getOsVersion()).isEqualTo("18.0");
        assertThat(second.getIsValid()).isTrue();
    }

    @Test
    @Order(3)
    void registerToken_defaultTokenType() {
        String uniqueToken = "test-default-type-" + System.currentTimeMillis();
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();

        PushDeviceToken result = deviceTokenService.registerToken(
                tenantId, userId, "android", uniqueToken,
                "device-003", null, "1.0.0", "14.0");

        assertThat(result).isNotNull();
        assertThat(result.getTokenType()).isEqualTo("apns"); // default
    }

    @Test
    @Order(4)
    void getValidTokens_returnsOnlyValidTokens() {
        String prefix = "test-valid-" + System.currentTimeMillis();
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();

        // Register two tokens
        deviceTokenService.registerToken(tenantId, userId, "ios", prefix + "-1",
                "dev-a", "apns", "1.0.0", "18.0");
        deviceTokenService.registerToken(tenantId, userId, "android", prefix + "-2",
                "dev-b", "fcm", "1.0.0", "14.0");

        List<PushDeviceToken> tokens = deviceTokenService.getValidTokens(tenantId, userId);
        assertThat(tokens).hasSizeGreaterThanOrEqualTo(2);

        // Verify all returned tokens are valid
        for (PushDeviceToken token : tokens) {
            assertThat(token.getIsValid()).isTrue();
            assertThat(token.getUserId()).isEqualTo(userId);
        }
    }

    @Test
    @Order(5)
    void unregisterToken_softDeletes() {
        String uniqueToken = "test-unregister-" + System.currentTimeMillis();
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();

        // Register then unregister
        deviceTokenService.registerToken(tenantId, userId, "ios", uniqueToken,
                "device-del", "apns", "1.0.0", "18.0");

        deviceTokenService.unregisterToken(tenantId, userId, uniqueToken);

        // Verify token is no longer returned by getValidTokens
        List<PushDeviceToken> tokens = deviceTokenService.getValidTokens(tenantId, userId);
        boolean found = tokens.stream()
                .anyMatch(t -> uniqueToken.equals(t.getPushToken()));
        assertThat(found).isFalse();
    }

    @Test
    @Order(6)
    void invalidateToken_marksAsInvalid() {
        String uniqueToken = "test-invalidate-" + System.currentTimeMillis();
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();

        PushDeviceToken token = deviceTokenService.registerToken(
                tenantId, userId, "ios", uniqueToken,
                "device-inv", "apns", "1.0.0", "18.0");

        deviceTokenService.invalidateToken(token.getId());

        // Verify token is no longer returned by getValidTokens
        List<PushDeviceToken> tokens = deviceTokenService.getValidTokens(tenantId, userId);
        boolean found = tokens.stream()
                .anyMatch(t -> uniqueToken.equals(t.getPushToken()));
        assertThat(found).isFalse();
    }

    @Test
    @Order(7)
    void registerToken_reactivatesSoftDeletedToken() {
        String uniqueToken = "test-reactivate-" + System.currentTimeMillis();
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();

        // Register, unregister, then re-register
        PushDeviceToken first = deviceTokenService.registerToken(
                tenantId, userId, "ios", uniqueToken,
                "device-r1", "apns", "1.0.0", "18.0");

        deviceTokenService.unregisterToken(tenantId, userId, uniqueToken);

        PushDeviceToken reactivated = deviceTokenService.registerToken(
                tenantId, userId, "ios", uniqueToken,
                "device-r2", "apns", "2.0.0", "18.1");

        assertThat(reactivated).isNotNull();
        assertThat(reactivated.getId()).isEqualTo(first.getId());
        assertThat(reactivated.getIsValid()).isTrue();
        assertThat(reactivated.getDeletedFlag()).isFalse();
        assertThat(reactivated.getDeviceId()).isEqualTo("device-r2");
        assertThat(reactivated.getAppVersion()).isEqualTo("2.0.0");
    }
}
