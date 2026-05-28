package com.auraboot.framework.plugin.extension.iot;

import com.auraboot.framework.plugin.extension.iot.BackgroundIotCredentialAccessor.CredentialType;
import com.auraboot.framework.plugin.extension.iot.BackgroundIotCredentialAccessor.IotCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@link BackgroundIotCredentialAccessor} using an
 * in-memory fake. Verifies issue rotates material, revoke removes the
 * tenant-scoped row, and {@code syncAclToBroker} is idempotent.
 */
class BackgroundIotCredentialAccessorContractTest {

    private InMemoryCredentialAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = new InMemoryCredentialAccessor();
    }

    @Test
    void issueCredentials_returnsFreshAccessToken() {
        IotCredentials creds = accessor.issueCredentials(100L, "sensor-A", CredentialType.ACCESS_TOKEN);

        assertThat(creds).isNotNull();
        assertThat(creds.type()).isEqualTo(CredentialType.ACCESS_TOKEN);
        assertThat(creds.secret()).isNotBlank();
        assertThat(creds.jwt()).isNull();
        assertThat(creds.aclPatterns()).isNotEmpty();
    }

    @Test
    void issueCredentials_jwtPopulatesJwtFieldNotSecret() {
        IotCredentials creds = accessor.issueCredentials(100L, "sensor-A", CredentialType.JWT);

        assertThat(creds.type()).isEqualTo(CredentialType.JWT);
        assertThat(creds.jwt()).isNotBlank();
        assertThat(creds.expiresAt()).isAfter(Instant.now().minusSeconds(1));
    }

    @Test
    void issueCredentials_rotatesOnRepeatCall() {
        IotCredentials first = accessor.issueCredentials(100L, "sensor-A", CredentialType.ACCESS_TOKEN);
        IotCredentials second = accessor.issueCredentials(100L, "sensor-A", CredentialType.ACCESS_TOKEN);

        assertThat(first.secret()).isNotEqualTo(second.secret());
    }

    @Test
    void issueCredentials_isolatesAcrossTenants() {
        IotCredentials t100 = accessor.issueCredentials(100L, "sensor-A", CredentialType.MQTT_BASIC);
        IotCredentials t200 = accessor.issueCredentials(200L, "sensor-A", CredentialType.MQTT_BASIC);

        assertThat(t100.secret()).isNotEqualTo(t200.secret());
        assertThat(accessor.has(100L, "sensor-A")).isTrue();
        assertThat(accessor.has(200L, "sensor-A")).isTrue();
    }

    @Test
    void revokeCredentials_removesTenantScopedRow() {
        accessor.issueCredentials(100L, "sensor-A", CredentialType.ACCESS_TOKEN);
        assertThat(accessor.has(100L, "sensor-A")).isTrue();

        accessor.revokeCredentials(100L, "sensor-A");

        assertThat(accessor.has(100L, "sensor-A")).isFalse();
    }

    @Test
    void revokeCredentials_doesNotTouchOtherTenants() {
        accessor.issueCredentials(100L, "sensor-A", CredentialType.ACCESS_TOKEN);
        accessor.issueCredentials(200L, "sensor-A", CredentialType.ACCESS_TOKEN);

        accessor.revokeCredentials(100L, "sensor-A");

        assertThat(accessor.has(100L, "sensor-A")).isFalse();
        assertThat(accessor.has(200L, "sensor-A")).isTrue();
    }

    @Test
    void syncAclToBroker_isIdempotent() {
        accessor.issueCredentials(100L, "sensor-A", CredentialType.ACCESS_TOKEN);
        accessor.issueCredentials(100L, "sensor-B", CredentialType.ACCESS_TOKEN);

        accessor.syncAclToBroker(100L);
        accessor.syncAclToBroker(100L);

        assertThat(accessor.syncCallsFor(100L)).isEqualTo(2);
        assertThat(accessor.lastAclSnapshotFor(100L)).hasSize(2);
    }

    /** In-memory implementation used to assert the contract shape. */
    static final class InMemoryCredentialAccessor implements BackgroundIotCredentialAccessor {
        private final Map<String, IotCredentials> store = new HashMap<>();
        private final Map<Long, Integer> syncCounter = new HashMap<>();
        private final Map<Long, Set<String>> lastSnapshot = new HashMap<>();

        boolean has(long tenantId, String deviceCode) {
            return store.containsKey(tenantId + ":" + deviceCode);
        }

        int syncCallsFor(long tenantId) {
            return syncCounter.getOrDefault(tenantId, 0);
        }

        Set<String> lastAclSnapshotFor(long tenantId) {
            return lastSnapshot.getOrDefault(tenantId, Set.of());
        }

        @Override
        public IotCredentials issueCredentials(long tenantId, String deviceCode, CredentialType type) {
            String secret = type == CredentialType.JWT ? null : UUID.randomUUID().toString();
            String jwt = type == CredentialType.JWT ? "jwt-" + UUID.randomUUID() : null;
            Instant expiresAt = type == CredentialType.JWT ? Instant.now().plusSeconds(3600) : null;
            IotCredentials creds = new IotCredentials(type, secret, jwt,
                    List.of("/sys/" + tenantId + "/" + deviceCode + "/#"), expiresAt);
            store.put(tenantId + ":" + deviceCode, creds);
            return creds;
        }

        @Override
        public void revokeCredentials(long tenantId, String deviceCode) {
            store.remove(tenantId + ":" + deviceCode);
        }

        @Override
        public void syncAclToBroker(long tenantId) {
            syncCounter.merge(tenantId, 1, Integer::sum);
            Set<String> snapshot = new HashSet<>();
            String prefix = tenantId + ":";
            for (String k : store.keySet()) {
                if (k.startsWith(prefix)) {
                    snapshot.add(k.substring(prefix.length()));
                }
            }
            lastSnapshot.put(tenantId, snapshot);
        }
    }
}
