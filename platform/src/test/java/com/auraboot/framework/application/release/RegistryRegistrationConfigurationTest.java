package com.auraboot.framework.application.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class RegistryRegistrationConfigurationTest {
    @Test void disabledRegistrationDoesNotFallBackToRuntimeDatabase() {
        var service = new ApplicationReleaseRegistrationService(new MockEnvironment(), new ObjectMapper());
        assertThrows(ApplicationReleaseRegistrationService.RegistrationUnavailableException.class,
                () -> service.createApplication("test-app", "Test", "test:actor"));
        assertThrows(ApplicationReleaseRegistrationService.RegistrationUnavailableException.class,
                () -> service.register("test-app", "key", null, "test:actor"));
        service.close();
    }
    @Test void enabledRegistrationRequiresExplicitCredentialsAndDistinctAccount() {
        var env = new MockEnvironment().withProperty("aura.registry.registration.enabled", "true");
        assertThrows(IllegalArgumentException.class, () -> new ApplicationReleaseRegistrationService(env, new ObjectMapper()));
        env.withProperty("aura.registry.registration.jdbc-url", "jdbc:postgresql://localhost/test")
                .withProperty("aura.registry.registration.username", "runtime")
                .withProperty("aura.registry.registration.password-file", "/nonexistent/registry-secret")
                .withProperty("spring.datasource.username", "runtime");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new ApplicationReleaseRegistrationService(env, new ObjectMapper()));
        assertTrue(failure.getMessage().contains("distinct"));
    }
}
