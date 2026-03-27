package com.auraboot.framework.bpm.config;

import com.auraboot.smart.framework.engine.configuration.IdGenerator;
import com.auraboot.smart.framework.engine.configuration.ProcessEngineConfiguration;
import com.auraboot.smart.framework.engine.model.instance.Instance;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Ensures SmartEngine uses numeric IDs for database storage in tests.
 */
@Configuration
@Profile("test")
public class TestProcessEngineIdGeneratorConfig {

    @Bean
    public BeanPostProcessor processEngineIdGeneratorCustomizer() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof ProcessEngineConfiguration configuration) {
                    configuration.setIdGenerator(new NumericIdGenerator());
                }
                return bean;
            }
        };
    }

    private static class NumericIdGenerator implements IdGenerator {
        private final AtomicLong counter = new AtomicLong(1_000_000L);

        @Override
        public void generate(Instance instance) {
            instance.setInstanceId(String.valueOf(counter.getAndIncrement()));
        }
    }
}
