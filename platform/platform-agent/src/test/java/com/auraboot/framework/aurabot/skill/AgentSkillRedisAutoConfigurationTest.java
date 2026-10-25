package com.auraboot.framework.aurabot.skill;

import com.auraboot.autoconfigure.agent.AgentSkillRedisAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentSkillRedisAutoConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void redisAutoConfigurationRegistersStoresWithoutComponentScanOrdering() {
        context.withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
                .withConfiguration(AutoConfigurations.of(AgentSkillRedisAutoConfiguration.class,
                        RedisAutoConfiguration.class))
                .run(application -> {
                    assertThat(application).hasSingleBean(PreviewTokenStore.class);
                    assertThat(application).hasSingleBean(SkillIdempotencyStore.class);
                });
    }

    @Test
    void absentRedisKeepsStoresAbsent() {
        context.withConfiguration(AutoConfigurations.of(AgentSkillRedisAutoConfiguration.class))
                .run(application -> {
                    assertThat(application).doesNotHaveBean(PreviewTokenStore.class);
                    assertThat(application).doesNotHaveBean(SkillIdempotencyStore.class);
                });
    }
}
