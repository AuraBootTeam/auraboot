package com.auraboot.framework.application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Test Spring Boot Application Configuration
 * Used for unit tests and integration tests.
 * Sibling bootstraps stay outside this scan; per-test components require explicit imports.
 */
@SpringBootApplication(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
@ComponentScan(basePackages = {"com.auraboot.framework", "com.auraboot.module"}, excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM,
                classes = {TypeExcludeFilter.class, AutoConfigurationExcludeFilter.class}),
        @ComponentScan.Filter(type = FilterType.ANNOTATION,
                classes = {SpringBootConfiguration.class, TestComponent.class})
})
@org.mybatis.spring.annotation.MapperScan({
        "com.auraboot.framework.**.mapper",
        "com.auraboot.framework.*.dao",
        "com.auraboot.framework.application.release",
        "com.auraboot.module.*.mapper",
        "com.auraboot.module.meta.excel.mapper",
        "com.auraboot.smart.framework.engine.persister.database"
})
@EnableScheduling
@org.springframework.transaction.annotation.EnableTransactionManagement
public class TestApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }
}