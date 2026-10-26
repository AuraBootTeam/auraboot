package com.auraboot.framework.plugin.extension;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RecordActionScopeAccessorTest {
    @Test void absentOrUntypedSettingsDoNotManufactureAuthority() {
        assertNull(CommandHandlerExtension.CommandContext.builder().build().recordActionScopes());
        assertNull(CommandHandlerExtension.CommandContext.builder().settings(Map.of(
                RecordActionScopeAccessor.SETTINGS_KEY, Map.of("allowed", true))).build().recordActionScopes());
    }
    @Test void handlerAndInvocationContextsRetainOnlyTheTypedHostBridge() {
        RecordActionScopeAccessor bridge = (model, pid, action) -> false;
        assertSame(bridge, CommandHandlerExtension.CommandContext.builder().settings(Map.of(
                RecordActionScopeAccessor.SETTINGS_KEY, bridge)).build().recordActionScopes());
        var payload = new HashMap<String,Object>(Map.of("parentPid", "parent-1"));
        var context = new CommandInvocationAuthorizer.InvocationContext(1L, 2L, "user", "create", "handler",
                "child", null, payload, bridge);
        payload.put("parentPid", "forged");
        assertEquals("parent-1", context.payload().get("parentPid"));
        assertSame(bridge, context.recordScopes());
        assertThrows(UnsupportedOperationException.class, () -> context.payload().clear());
    }
    @Test void theExistingInvocationConstructorRemainsAvailableWithoutAnImplicitGrant() {
        var context = new CommandInvocationAuthorizer.InvocationContext(1L, 2L, "user", "create", "handler",
                "child", null, Map.of());
        assertNull(context.recordScopes());
    }
}
