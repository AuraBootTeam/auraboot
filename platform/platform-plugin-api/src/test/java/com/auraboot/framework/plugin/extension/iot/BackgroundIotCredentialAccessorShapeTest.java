package com.auraboot.framework.plugin.extension.iot;

import com.auraboot.framework.plugin.extension.iot.BackgroundIotCredentialAccessor.CredentialType;
import com.auraboot.framework.plugin.extension.iot.BackgroundIotCredentialAccessor.IotCredentials;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shape-only pin of the {@link BackgroundIotCredentialAccessor} SPI records
 * ({@link CredentialType}, {@link IotCredentials}), per testing-layering.md
 * §2.1 (shape self-check exemption).
 *
 * Issue/rotate/revoke semantics, tenant isolation and broker-sync idempotence
 * are persistence behavior: they are NOT asserted here — the former fake's
 * own issue/revoke implementation was asserted against itself. They are owed
 * to real-stack tests against {@code IotCredentialAccessorImpl}; tracked in
 * workspace {@code tools/fake-db-registry.tsv}.
 */
class BackgroundIotCredentialAccessorShapeTest {

    @Test
    void credentialTypeEnumExposesTheDocumentedKinds() {
        assertThat(CredentialType.values())
                .containsExactly(CredentialType.ACCESS_TOKEN, CredentialType.X509_CERTIFICATE,
                        CredentialType.MQTT_BASIC, CredentialType.JWT);
    }

    @Test
    void iotCredentialsRecordCarriesTypeSecretJwtAclAndExpiry() {
        IotCredentials token = new IotCredentials(CredentialType.ACCESS_TOKEN,
                "raw-token", null, List.of("/sys/100/sensor-A/#"), null);
        IotCredentials jwt = new IotCredentials(CredentialType.JWT,
                null, "signed-jwt", List.of(), Instant.parse("2026-06-01T00:00:00Z"));

        assertThat(token.type()).isEqualTo(CredentialType.ACCESS_TOKEN);
        assertThat(token.secret()).isEqualTo("raw-token");
        assertThat(token.jwt()).isNull();
        assertThat(token.aclPatterns()).containsExactly("/sys/100/sensor-A/#");
        assertThat(token.expiresAt()).isNull();

        assertThat(jwt.type()).isEqualTo(CredentialType.JWT);
        assertThat(jwt.jwt()).isEqualTo("signed-jwt");
        assertThat(jwt.secret()).isNull();
        assertThat(jwt.aclPatterns()).isEmpty();
        assertThat(jwt.expiresAt()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
    }
}
