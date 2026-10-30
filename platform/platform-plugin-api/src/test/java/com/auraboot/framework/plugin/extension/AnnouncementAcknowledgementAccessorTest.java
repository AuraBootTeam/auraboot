package com.auraboot.framework.plugin.extension;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static com.auraboot.framework.plugin.extension.AnnouncementAcknowledgementAccessor.*;
import static org.junit.jupiter.api.Assertions.*;

class AnnouncementAcknowledgementAccessorTest {
    private static final String HASH = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");

    @Test void oldHostsAndPlainSettingsCannotManufactureACapability() {
        assertNull(CommandHandlerExtension.CommandContext.builder().build().announcementAcknowledgementAccessor());
        var context = CommandHandlerExtension.CommandContext.builder().settings(Map.of(SETTINGS_KEY, Map.of("status", "SATISFIED"))).build();
        assertNull(context.announcementAcknowledgementAccessor());
    }

    @Test void aHostInstalledBridgeRetainsItsExactType() {
        AnnouncementAcknowledgementAccessor bridge = requirements -> List.of();
        var context = CommandHandlerExtension.CommandContext.builder().settings(Map.of(SETTINGS_KEY, bridge)).build();
        assertSame(bridge, context.announcementAcknowledgementAccessor());
    }

    @Test void requirementsNeedExactPublicIdentitiesAndAFullSnapshotHash() {
        assertThrows(IllegalArgumentException.class, () -> new Requirement("", HASH, "member"));
        assertThrows(IllegalArgumentException.class, () -> new Requirement("version", "hash", "member"));
        assertThrows(IllegalArgumentException.class, () -> new Requirement("version", HASH, null));
        assertEquals(HASH, new Requirement("version", HASH, "member").snapshotHash());
    }

    @Test void satisfiedCannotMeanReadOnlyWrongVersionWrongHashOrExpired() {
        assertThrows(IllegalArgumentException.class, () -> proof("other-version", HASH, true, true, true, true, NOW.minusSeconds(1), NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> proof("version", "b".repeat(64), true, true, true, true, NOW.minusSeconds(1), NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> proof("version", HASH, false, true, true, true, NOW.minusSeconds(1), NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> proof("version", HASH, true, false, true, true, NOW.minusSeconds(1), NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> proof("version", HASH, true, true, false, true, NOW.minusSeconds(1), NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> proof("version", HASH, true, true, true, false, NOW.minusSeconds(1), NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> proof("version", HASH, true, true, true, true, null, NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> proof("version", HASH, true, true, true, true, NOW.plusSeconds(1), NOW.plusSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> proof("version", HASH, true, true, true, true, NOW.minusSeconds(1), NOW));
        assertEquals(Status.SATISFIED, proof("version", HASH, true, true, true, true, NOW.minusSeconds(1), NOW.plusSeconds(1)).status());
    }

    private Check proof(String active, String ackHash, boolean effective, boolean visible,
                        boolean member, boolean required, Instant acknowledged, Instant expiry) {
        return new Check("version", active, HASH, Status.SATISFIED, effective, visible, member,
                required, acknowledged, ackHash, NOW, expiry);
    }
}
