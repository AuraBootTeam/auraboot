package com.auraboot.framework.application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Test Spring Boot Application Configuration
 * Used for unit tests and integration tests.
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.auraboot.framework")
@org.mybatis.spring.annotation.MapperScan({"com.auraboot.framework.*.mapper", "com.auraboot.framework.*.dao.mapper", "com.auraboot.framework.*.dao", "com.auraboot.framework.aurabot.skill.mapper", "com.auraboot.framework.saas.config.mapper", "com.auraboot.framework.saas.account.mapper", "com.auraboot.framework.saas.license.mapper", "com.auraboot.smart.framework.engine.persister.database"})
@org.springframework.transaction.annotation.EnableTransactionManagement
public class TestApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }
}