package com.auraboot.framework.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.mockito.Mockito;

/**
 * The reliable-integration indicator is business backlog state, not host infrastructure.
 * Host-first golden stacks must be able to remove it from the aggregate /actuator/health
 * through {@code MANAGEMENT_HEALTH_RELIABLEINTEGRATION_ENABLED=false} while production
 * keeps the default (registered). The lowercase single-segment property name is deliberate:
 * its SYSTEM env mapping is exact, with no relaxed-binding ambiguity.
 */
class ReliableIntegrationHealthConditionalTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(OutboxEventMapper.class, () -> Mockito.mock(OutboxEventMapper.class))
            .withUserConfiguration(ReliableIntegrationHealth.class);

    @Test
    void indicatorRegistersByDefaultSoProductionKeepsTheFailVisibleAlert() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("reliableIntegration");
        });
    }

    @Test
    void explicitTrueStillRegistersTheIndicator() {
        runner.withPropertyValues("management.health.reliableintegration.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("reliableIntegration");
        });
    }

    @Test
    void goldenStacksRemoveTheBusinessBacklogSignalFromAggregateHealth() {
        runner.withPropertyValues("management.health.reliableintegration.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("reliableIntegration");
        });
    }
}
