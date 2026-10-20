package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.openplatform.entity.ApplicationAccessToken;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationTokenPolicyTest {
    private final Instant now = Instant.parse("2026-10-15T00:00:00Z");

    @Test void missingExpiryNeverAccidentallyCreatesPermanentAuthority() {
        assertEquals(now.plus(Duration.ofDays(30)), IntegrationTokenPolicy.expiry(null, null, now));
        assertNull(IntegrationTokenPolicy.expiry("never", null, now));
        assertThrows(IllegalArgumentException.class, () -> IntegrationTokenPolicy.expiry("never", now.plusSeconds(3600), now));
        assertThrows(IllegalArgumentException.class, () -> IntegrationTokenPolicy.expiry("invalid", null, now));
    }
    @Test void acceptsExactBoundsAndRejectsExpiredOrExcessiveAuthority() {
        for (Duration lifetime : new Duration[]{Duration.ofHours(1), Duration.ofDays(90)}) {
            Instant until = now.plus(lifetime);
            assertEquals(until, IntegrationTokenPolicy.expiry("fixed", until, now));
        }
        for (Instant until : new Instant[]{now, now.minusSeconds(1), now.plusSeconds(3599), now.plus(Duration.ofDays(90)).plusNanos(1)})
            assertThrows(IllegalArgumentException.class, () -> IntegrationTokenPolicy.expiry("fixed", until, now));
    }
    @Test void rotationNeverExtendsOldAuthorityAndPermanentTokensAcquireAFiniteCutoff() {
        Instant original = now.plusSeconds(7200);
        assertEquals(original, IntegrationTokenPolicy.rotationCutoff(now, null, original));
        assertEquals(now.plusSeconds(60), IntegrationTokenPolicy.rotationCutoff(now, 1, original));
        assertEquals(now, IntegrationTokenPolicy.rotationCutoff(now, 0, null));
        assertEquals(now.plus(Duration.ofDays(1)), IntegrationTokenPolicy.rotationCutoff(now, null, null));
        assertEquals(now.plus(Duration.ofDays(7)), IntegrationTokenPolicy.rotationCutoff(now, 10080, null));
        for (int invalid : new int[]{-1, 10081, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> IntegrationTokenPolicy.rotationCutoff(now, invalid, null));
    }
    @Test void permanentDoesNotBypassRevocationOrRotationAndBoundaryIsExclusive() {
        var token = new ApplicationAccessToken(); token.setNeverExpires(true);
        assertEquals("active", IntegrationTokenPolicy.status(token, now));
        token.setRotatedAt(now.minusSeconds(60)); token.setAcceptUntil(now);
        assertEquals("rotating", IntegrationTokenPolicy.status(token, now.minusNanos(1)));
        assertEquals("replaced", IntegrationTokenPolicy.status(token, now));
        token.setRevokedAt(now.minusSeconds(1));
        assertEquals("revoked", IntegrationTokenPolicy.status(token, now));
        token.setRevokedAt(null); token.setRotatedAt(null); token.setAcceptUntil(null);
        token.setNeverExpires(false); token.setExpiresAt(now);
        assertEquals("expired", IntegrationTokenPolicy.status(token, now));
        assertEquals("active", IntegrationTokenPolicy.status(token, now.minusNanos(1)));
    }
}
