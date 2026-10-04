package com.auraboot.framework.plugin.extension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class CommandContextBoundaryTest {
    @ParameterizedTest
    @MethodSource("bridges")
    void capabilitiesComeOnlyFromTypedHostSettings(String key, String getter, Class<?> type) throws Exception {
        var method = CommandHandlerExtension.CommandContext.class.getMethod(getter);
        assertEquals(type, method.getReturnType());
        Object bridge = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> { throw new AssertionError("Context lookup must not invoke a bridge"); });
        var payload = Map.<String, Object>of(key, bridge);
        assertNull(method.invoke(context(null, payload)));
        assertNull(method.invoke(context(Map.of(), payload)));
        assertNull(method.invoke(context(Map.of(key, "forged"), payload)));
        assertSame(bridge, method.invoke(context(Map.of(key, bridge), Map.of())));
    }
    static Stream<Arguments> bridges() {
        return Stream.of(Arguments.of("__dataAccessor", "dataAccessor", DataAccessor.class),
            Arguments.of("__tenantProjectionAccessor", "tenantProjectionAccessor", TenantProjectionAccessor.class),
            Arguments.of("__tenantRoleAssignmentAccessor", "tenantRoleAssignmentAccessor", TenantRoleAssignmentAccessor.class),
            Arguments.of("__identityDirectoryAccessor", "identityDirectoryAccessor", IdentityDirectoryAccessor.class),
            Arguments.of("__biTemporalAccessor", "biTemporalAccessor", BiTemporalAccessor.class),
            Arguments.of("__aiProviderAccessor", "aiProviderAccessor", AiProviderAccessor.class),
            Arguments.of("__fileAccessor", "fileAccessor", FileAccessor.class),
            Arguments.of("__asyncTaskAccessor", "asyncTaskAccessor", AsyncTaskAccessor.class),
            Arguments.of("__independentTransactionAccessor", "independentTransactionAccessor", IndependentTransactionAccessor.class),
            Arguments.of("__recordShareAccessor", "recordShareAccessor", RecordShareAccessor.class));
    }

    @Test
    void trustedIdentityAndVersionAreNotTakenFromBusinessPayload() {
        var payload = Map.<String, Object>of("__currentUserPid", "forged", "__clientRequestId", "forged", "__expectedVersion", 9);
        var missing = context(null, payload);
        assertNull(missing.currentUserPid()); assertNull(missing.clientRequestId()); assertNull(missing.expectedVersion());
        var blank = context(Map.of("__currentUserPid", " ", "__clientRequestId", " ", "__expectedVersion", "invalid"), payload);
        assertNull(blank.currentUserPid()); assertNull(blank.clientRequestId()); assertNull(blank.expectedVersion());
        var valid = context(Map.of("__currentUserPid", " user-pid ", "__clientRequestId", " request-id ", "__expectedVersion", " 12 "), payload);
        assertEquals("user-pid", valid.currentUserPid()); assertEquals("request-id", valid.clientRequestId()); assertEquals(12L, valid.expectedVersion());
        assertEquals(13L, context(Map.of("__expectedVersion", 13L), payload).expectedVersion());
        assertNull(context(Map.of(), payload).expectedVersion());
    }

    @Test
    void builderPreservesTheCommandBoundaryAndDryRunFlag() {
        var payload = Map.<String, Object>of("amount", 5); var settings = Map.<String, Object>of("tenant", 7);
        var context = CommandHandlerExtension.CommandContext.builder().tenantId(7L).pluginId("plugin").namespace("ns")
            .commandType("ns:approve").modelCode("ns_order").recordId("record").payload(payload).settings(settings).dryRun(true).build();
        assertEquals(7L, context.tenantId()); assertEquals("plugin", context.pluginId()); assertEquals("ns", context.namespace());
        assertEquals("ns:approve", context.commandType()); assertEquals("ns_order", context.modelCode()); assertEquals("record", context.recordId());
        assertSame(payload, context.payload()); assertSame(settings, context.settings()); assertTrue(context.dryRun());
        assertFalse(CommandHandlerExtension.CommandContext.builder().build().dryRun());
    }

    @Test
    void defaultHandlerContractsDenyDryRunAndAliasesUnlessDeclared() {
        var handler = handler("ns:approve");
        assertEquals(Set.of("ns:approve"), handler.getSupportedCommandTypes()); assertTrue(handler.supports("ns:approve"));
        assertFalse(handler.supports("ns:other")); assertFalse(handler.supports(null)); assertEquals(0, handler.getPriority());
        assertFalse(handler.chainsAfterPrimary()); assertFalse(handler.supportsDryRun());
    }

    @Test
    void missingHandlerTypeAdvertisesAndMatchesNoCommands() {
        var handler = handler(null);
        assertEquals(Set.of(), handler.getSupportedCommandTypes());
        assertFalse(handler.supports("ns:approve")); assertFalse(handler.supports(null));
    }

    private CommandHandlerExtension.CommandContext context(Map<String, Object> settings, Map<String, Object> payload) {
        return new CommandHandlerExtension.CommandContext(7L, "plugin", "ns", "ns:approve", "ns_order", "record", payload, settings, false);
    }
    private CommandHandlerExtension handler(String type) {
        return new CommandHandlerExtension() { public String getCommandType() { return type; } public Object execute(CommandContext context) { return null; } };
    }
}
