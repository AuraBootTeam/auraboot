package com.auraboot.framework.action;

import com.auraboot.framework.action.executor.BpmActionExecutor;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BpmActionExecutor (stub during refactor — full tests in Task 6)")
class BpmActionExecutorIntegrationTest extends BaseIntegrationTest {

    @Autowired private BpmActionExecutor executor;

    @Test
    @DisplayName("supports() identifies bpm executionMode")
    void supportsIdentifiesBpm() {
        assertThat(executor.supports("bpm")).isTrue();
        assertThat(executor.supports("command")).isFalse();
    }
}
