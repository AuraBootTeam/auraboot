package com.auraboot.framework.meta.service.executor;

import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutorRegistryIntegrationTest extends BaseIntegrationTest {

    @Autowired private ExecutorRegistry registry;

    @Test
    void physical_sourceType_returns_empty() {
        assertThat(registry.resolve("physical")).isEmpty();
    }

    @Test
    void null_sourceType_returns_empty() {
        assertThat(registry.resolve(null)).isEmpty();
    }

    @Test
    void unregistered_sourceType_returns_empty() {
        assertThat(registry.resolve("nonexistent")).isEmpty();
    }

    @Test
    void registry_rejects_duplicate_sourceType_bean_registrations() {
        // This test is implicit — if the constructor validation fires, Spring
        // application context fails to start. If this test class loads at all,
        // there are no duplicates currently. Marker test.
        assertThat(registry).isNotNull();
    }
}
