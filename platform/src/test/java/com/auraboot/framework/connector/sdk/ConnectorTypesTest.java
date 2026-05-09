package com.auraboot.framework.connector.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Connector SDK base types")
class ConnectorTypesTest {

    @Test
    @DisplayName("Connector record carries pid + tenantId + protocolType + displayName")
    void testConnectorRecordShape() {
        Connector c = new Connector("01HXYZ", 42L, "jdbc", "Sales DB", true);
        assertEquals("01HXYZ", c.pid());
        assertEquals(42L, c.tenantId());
        assertEquals("jdbc", c.protocolType());
        assertEquals("Sales DB", c.displayName());
        assertTrue(c.enabled());
    }

    @Test
    @DisplayName("ConnectorEndpoint record carries code + connectorPid + displayName")
    void testConnectorEndpointRecordShape() {
        ConnectorEndpoint e = new ConnectorEndpoint("01EP", "01HXYZ", "list-users", "List Users",
                Map.of("limit", "int"), Map.of("$.id", "userId"));
        assertEquals("01EP", e.pid());
        assertEquals("01HXYZ", e.connectorPid());
        assertEquals("list-users", e.code());
        assertEquals("List Users", e.displayName());
        assertEquals("int", e.requestSchema().get("limit"));
        assertEquals("userId", e.responseMapping().get("$.id"));
    }

    @Test
    @DisplayName("ConnectorDescriptor lists supported endpoint codes")
    void testDescriptorListsEndpoints() {
        ConnectorDescriptor d = new ConnectorDescriptor(
                "jdbc", "MySQL/Postgres JDBC connector", List.of("query", "update"));
        assertEquals("jdbc", d.protocolType());
        assertEquals(2, d.supportedEndpointCodes().size());
        assertTrue(d.supportedEndpointCodes().contains("query"));
    }

    @Test
    @DisplayName("ConnectorInvocationContext carries tenantId + connectorPid + params + dryRun")
    void testInvocationContextShape() {
        ConnectorInvocationContext ctx = new ConnectorInvocationContext(
                42L, "01HXYZ", "list-users", Map.of("limit", 10), false);
        assertEquals(42L, ctx.tenantId());
        assertEquals("01HXYZ", ctx.connectorPid());
        assertEquals("list-users", ctx.endpointCode());
        assertEquals(10, ctx.params().get("limit"));
        assertFalse(ctx.dryRun());
    }

    @Test
    @DisplayName("ConnectorInvocationResult.success builds OK envelope")
    void testInvocationResultSuccess() {
        ConnectorInvocationResult r = ConnectorInvocationResult.success(Map.of("rows", 3));
        assertTrue(r.success());
        assertEquals(3, ((Map<?, ?>) r.data()).get("rows"));
        assertNull(r.errorMessage());
    }

    @Test
    @DisplayName("ConnectorInvocationResult.failure builds error envelope")
    void testInvocationResultFailure() {
        ConnectorInvocationResult r = ConnectorInvocationResult.failure("connection refused");
        assertFalse(r.success());
        assertNull(r.data());
        assertEquals("connection refused", r.errorMessage());
    }
}
