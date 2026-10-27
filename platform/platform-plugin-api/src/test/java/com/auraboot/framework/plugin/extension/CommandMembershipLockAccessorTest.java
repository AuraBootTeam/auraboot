package com.auraboot.framework.plugin.extension;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CommandMembershipLockAccessorTest {
    @Test void absentOrUntypedSettingsDoNotManufactureAHostCapability() {
        assertNull(CommandHandlerExtension.CommandContext.builder().build().commandMembershipLockAccessor());
        var context = CommandHandlerExtension.CommandContext.builder().settings(Map.of(
                CommandMembershipLockAccessor.SETTINGS_KEY, Map.of("locked", true))).build();
        assertNull(context.commandMembershipLockAccessor());
    }
    @Test void theInstalledBridgeRetainsItsExactPublicType() {
        CommandMembershipLockAccessor bridge = required -> List.of();
        var context = CommandHandlerExtension.CommandContext.builder().settings(Map.of(
                CommandMembershipLockAccessor.SETTINGS_KEY, bridge)).build();
        assertSame(bridge, context.commandMembershipLockAccessor());
    }
    @Test void assertionsRejectAliasesAndNonPublicControlText() {
        for (String value : new String[]{"", " ", " member", "member ", "m\n", "a\u200bb", "a".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> new CommandMembershipLockAccessor.MembershipAssertion("user", value));
            assertThrows(IllegalArgumentException.class, () -> new CommandMembershipLockAccessor.MembershipAssertion(value, "member"));
        }
        assertThrows(IllegalArgumentException.class, () -> new CommandMembershipLockAccessor.MembershipAssertion(null, "member"));
        assertEquals("member-public", new CommandMembershipLockAccessor.MembershipAssertion("user-public", "member-public").memberPid());
    }
}
