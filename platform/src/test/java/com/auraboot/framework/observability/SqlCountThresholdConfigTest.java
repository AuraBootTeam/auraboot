package com.auraboot.framework.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class SqlCountThresholdConfigTest {

    @Test
    void defaultSqlCountThresholdsMatchDocumentedWarnAndCriticalLevels() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));

        Properties properties = factory.getObject();

        assertThat(properties.get("auraboot.performance.sql-count-warn-threshold")).isEqualTo(50);
        assertThat(properties.get("auraboot.performance.sql-count-error-threshold")).isEqualTo(100);
    }

    @Test
    void testFixtureEndpointsDoNotEmitSqlCountSeverityLogs() {
        assertThat(SqlCountFilter.shouldLogSqlCountSeverity("/api/test/seed")).isFalse();
        assertThat(SqlCountFilter.shouldLogSqlCountSeverity("/api/test/fixture/run-1")).isFalse();
        assertThat(SqlCountFilter.shouldLogSqlCountSeverity("/api/meta/commands/execute/cr_crawl_job:start")).isTrue();
    }
}
