package com.auraboot.framework.openplatform.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiCapabilityRegistryTest {
    @Test
    void onlyResolvesExplicitMethodAndPathDeclarations() {
        OpenApiCapabilityRegistry registry = new OpenApiCapabilityRegistry(List.of());

        assertEquals("openapi.profile.read",
                registry.resolve("GET", "/api/open/v1/whoami").orElseThrow().requiredScope());
        assertTrue(registry.resolve("POST", "/api/open/v1/whoami").isEmpty());
        assertTrue(registry.resolve("GET", "/api/open/v1/internal/users").isEmpty());
    }

    @Test
    void rejectsDuplicateContributionsAtStartup() {
        OpenApiCapabilityContributor duplicate = () -> List.of(
                new OpenApiCapabilityRegistry.Capability("platform.whoami", HttpMethod.POST,
                        "/api/open/v1/another", "another.read", "tenant-wide", 1));

        assertThrows(IllegalStateException.class,
                () -> new OpenApiCapabilityRegistry(List.of(duplicate)));
    }
}
