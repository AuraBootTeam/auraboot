package com.auraboot.framework.iot.security;

import com.auraboot.framework.iot.security.IotJwtService.AclEntry;
import com.auraboot.framework.iot.security.IotJwtService.IotDeviceJwtClaims;
import com.auraboot.framework.meta.exception.MetaServiceException;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IotJwtServiceTest {

    private IotJwtService service;

    @BeforeEach
    void setUp() {
        IotJwtProperties props = new IotJwtProperties();
        props.setAlgorithm("HS256");
        // 32-byte raw key encoded base64.
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            keyBytes[i] = (byte) (i + 1);
        }
        props.setSecret(Base64.getEncoder().encodeToString(keyBytes));
        props.setTtl(Duration.ofHours(1));
        props.setIssuer("auraboot-iot-test");
        service = new IotJwtService(props);
        service.init();
    }

    @Test
    void issueAndVerify_roundTrip_carriesAclClaim() {
        IotDeviceJwtClaims claims = new IotDeviceJwtClaims(
                42L, "pk-air", "dev-1", "iot-ULID-1",
                List.of(new AclEntry("pub", "/sys/pk-air/dev-1/telemetry"),
                        new AclEntry("sub", "/sys/pk-air/dev-1/commands")));

        String jwt = service.issueDeviceJwt(claims);
        assertThat(jwt).isNotBlank().contains(".");

        Claims got = service.verifyDeviceJwt(jwt);
        assertThat(got.getSubject()).isEqualTo("dev-1");
        assertThat(got.getIssuer()).isEqualTo("auraboot-iot-test");
        assertThat(got.get("product_key")).isEqualTo("pk-air");
        assertThat(got.get("device_code")).isEqualTo("dev-1");
        assertThat(got.get("iot_id")).isEqualTo("iot-ULID-1");
        // tenant_id may be returned as Integer or Long depending on JSON.
        assertThat(((Number) got.get("tenant_id")).longValue()).isEqualTo(42L);
        Object aclObj = got.get("acl");
        assertThat(aclObj).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> acl = (List<Map<String, String>>) aclObj;
        assertThat(acl).hasSize(2);
        assertThat(acl.get(0)).containsEntry("action", "pub")
                .containsEntry("topic", "/sys/pk-air/dev-1/telemetry");
    }

    @Test
    void verifyDeviceJwt_rejectsTamperedSignature() {
        IotDeviceJwtClaims claims = new IotDeviceJwtClaims(
                42L, "pk", "d", "iot", List.of());
        String jwt = service.issueDeviceJwt(claims);
        // Flip a character in the signature segment.
        int lastDot = jwt.lastIndexOf('.');
        String tampered = jwt.substring(0, lastDot + 1) + "ZZZZ" + jwt.substring(lastDot + 5);
        assertThatThrownBy(() -> service.verifyDeviceJwt(tampered))
                .isInstanceOf(MetaServiceException.class);
    }

    @Test
    void verifyDeviceJwt_rejectsExpiredToken() throws InterruptedException {
        IotJwtProperties p = new IotJwtProperties();
        p.setAlgorithm("HS256");
        byte[] k = new byte[32];
        for (int i = 0; i < 32; i++) k[i] = 1;
        p.setSecret(Base64.getEncoder().encodeToString(k));
        p.setTtl(Duration.ofMillis(1));
        IotJwtService shortLived = new IotJwtService(p);
        shortLived.init();

        String jwt = shortLived.issueDeviceJwt(
                new IotDeviceJwtClaims(1L, "p", "d", "i", List.of()));
        Thread.sleep(50);
        assertThatThrownBy(() -> shortLived.verifyDeviceJwt(jwt))
                .isInstanceOf(MetaServiceException.class);
    }

    @Test
    void issue_throwsOnBlankOrNullInputs() {
        assertThatThrownBy(() -> service.issueDeviceJwt(null))
                .isInstanceOf(MetaServiceException.class);
        assertThatThrownBy(() -> service.verifyDeviceJwt(null))
                .isInstanceOf(MetaServiceException.class);
        assertThatThrownBy(() -> service.verifyDeviceJwt(""))
                .isInstanceOf(MetaServiceException.class);
    }

    @Test
    void init_rejectsShortSecret() {
        IotJwtProperties p = new IotJwtProperties();
        p.setAlgorithm("HS256");
        p.setSecret(Base64.getEncoder().encodeToString(new byte[16])); // 16 bytes < 32
        IotJwtService s = new IotJwtService(p);
        assertThatThrownBy(s::init).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void init_rejectsUnsupportedAlgorithm() {
        IotJwtProperties p = new IotJwtProperties();
        p.setAlgorithm("NONE");
        IotJwtService s = new IotJwtService(p);
        assertThatThrownBy(s::init).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void issue_failsWhenSecretBlank() {
        IotJwtProperties p = new IotJwtProperties();
        p.setAlgorithm("HS256");
        p.setSecret("");
        IotJwtService s = new IotJwtService(p);
        s.init(); // warns but does not throw
        assertThatThrownBy(() -> s.issueDeviceJwt(
                new IotDeviceJwtClaims(1L, "p", "d", "i", List.of())))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("jwt_not_configured");
    }
}
