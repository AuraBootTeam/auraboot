package com.auraboot.framework.application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;

/**
 * Test Spring Boot Application Configuration
 * Used for unit tests and integration tests.
 *
 * <p>Only explicitly imported test configurations may contribute fixture beans.
 * Other entry points are excluded from scanning so their component scans cannot
 * import unrelated test configurations or silently supply missing mapper packages.
 */
@SpringBootApplication
@ComponentScan(
    basePackages = {"com.auraboot.framework", "com.auraboot.module"},
    excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = SpringBootConfiguration.class)
    }
)
@org.mybatis.spring.annotation.MapperScan({
        "com.auraboot.framework.**.mapper",
        "com.auraboot.framework.application.release",
        "com.auraboot.module.*.mapper",
        "com.auraboot.module.meta.excel.mapper",
        "com.auraboot.framework.*.mapper", "com.auraboot.framework.*.dao.mapper", "com.auraboot.framework.*.dao", "com.auraboot.framework.aurabot.skill.mapper", "com.auraboot.framework.connector.saas.oauth.mapper", "com.auraboot.framework.connector.airflow.mapper", "com.auraboot.framework.connector.airflow.secret.mapper", "com.auraboot.framework.saas.config.mapper", "com.auraboot.framework.saas.account.mapper", "com.auraboot.framework.saas.license.mapper", "com.auraboot.smart.framework.engine.persister.database",
        "com.auraboot.framework.billing.catalog.mapper",
        "com.auraboot.framework.billing.quota.mapper",
        "com.auraboot.framework.billing.metering.mapper",
        "com.auraboot.framework.billing.account.mapper"})
@org.springframework.transaction.annotation.EnableTransactionManagement
public class TestApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }
}