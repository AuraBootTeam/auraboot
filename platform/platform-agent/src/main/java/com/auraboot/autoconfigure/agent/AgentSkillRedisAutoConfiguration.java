package com.auraboot.autoconfigure.agent;

import com.auraboot.framework.aurabot.skill.PreviewTokenStore;
import com.auraboot.framework.aurabot.skill.SkillIdempotencyStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Registers optional skill stores after Redis bean definitions are available. */
@AutoConfiguration(after = RedisAutoConfiguration.class)
@ConditionalOnBean(StringRedisTemplate.class)
public class AgentSkillRedisAutoConfiguration {
    @Bean
    PreviewTokenStore previewTokenStore(StringRedisTemplate redis, ObjectMapper mapper) {
        return new PreviewTokenStore(redis, mapper);
    }

    @Bean
    SkillIdempotencyStore skillIdempotencyStore(StringRedisTemplate redis) {
        return new SkillIdempotencyStore(redis);
    }
}
