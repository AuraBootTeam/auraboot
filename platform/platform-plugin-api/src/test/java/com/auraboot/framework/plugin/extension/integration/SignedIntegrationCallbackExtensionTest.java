package com.auraboot.framework.plugin.extension.integration;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SignedIntegrationCallbackExtensionTest {
    @Test void compiledDefinitionRequiresFiniteSelectorsAndItsOwnSourceEventType() {
        assertDoesNotThrow(() -> definition("sample-fees", "external.sample-logistics.fee.v1", "callbackSalt"));
        assertThrows(IllegalArgumentException.class, () -> definition("../dynamic", "external.sample-logistics.fee.v1", "callbackSalt"));
        assertThrows(IllegalArgumentException.class, () -> definition("sample-fees", "external.foreign.fee.v1", "callbackSalt"));
        assertThrows(IllegalArgumentException.class, () -> definition("sample-fees", "external.sample-logistics.fee.v1", "*"));
        assertThrows(IllegalArgumentException.class, () -> definition("sample-fees", "external.sample-logistics.fee.v1", "callbackInstallationPid"));
    }
    private SignedIntegrationCallbackExtension.Definition definition(String code, String event, String field) {
        return new SignedIntegrationCallbackExtension.Definition(code, "sample-logistics", event,
                "integration", "sample", field, "callbackInstallationPid");
    }
    @Test void requestCopiesNestedJsonAndCannotInjectHostIdentity() {
        var items = new ArrayList<>(List.of("one")); var nested = new LinkedHashMap<String,Object>(); nested.put("items", items);
        var request = request(Map.of("facts", nested)); items.add("two"); nested.put("new", true);
        assertEquals(Map.of("facts", Map.of("items", List.of("one"))), request.payload());
        assertThrows(UnsupportedOperationException.class, () -> request.payload().put("other", true));
        assertThrows(IllegalArgumentException.class, () -> request(Map.of("requestId", "caller-id")));
        assertThrows(IllegalArgumentException.class, () -> request(Map.of("externalSubject", "foreign")));
        assertThrows(IllegalArgumentException.class, () -> request(Map.of("object", new Object())));
    }
    @Test void absentOrMalformedIdentityCannotBeAVerifiedRequest() {
        assertThrows(IllegalArgumentException.class, () -> new SignedIntegrationCallbackExtension.Request("short", "shipment", "shipment-1", Instant.EPOCH, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new SignedIntegrationCallbackExtension.Request("request-123", "shipment", "../foreign", Instant.EPOCH, Map.of()));
        assertThrows(NullPointerException.class, () -> new SignedIntegrationCallbackExtension.Request("request-123", "shipment", "shipment-1", null, Map.of()));
    }
    private SignedIntegrationCallbackExtension.Request request(Map<String,Object> payload) {
        return new SignedIntegrationCallbackExtension.Request("request-123", "shipment", "shipment-1", Instant.EPOCH, payload);
    }
}
