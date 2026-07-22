package com.auraboot.framework.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transaction/datasource loggers used to be pinned to DEBUG as literals, which made every
 * deployment pay for per-statement transaction logs with no way to turn them down. They now
 * default to INFO through a placeholder, so an operator can raise them per environment.
 */
class LoggingLevelDefaultsTest {

    private static final String TX_INTERCEPTOR = "logging.level.org.springframework.transaction.interceptor";
    private static final String TX_SUPPORT = "logging.level.org.springframework.transaction.support";
    private static final String JDBC_DATASOURCE = "logging.level.org.springframework.jdbc.datasource";

    private static Properties applicationYaml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        return factory.getObject();
    }

    private static StandardEnvironment environmentWith(Map<String, Object> fakeEnvVars) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new PropertiesPropertySource("application.yml", applicationYaml()));
        environment.getPropertySources().addFirst(new MapPropertySource("fake-env", fakeEnvVars));
        return environment;
    }

    @Test
    void transactionAndDatasourceLoggersDefaultToInfo() {
        StandardEnvironment environment = environmentWith(Map.of());

        assertThat(environment.getProperty(TX_INTERCEPTOR)).isEqualTo("INFO");
        assertThat(environment.getProperty(TX_SUPPORT)).isEqualTo("INFO");
        assertThat(environment.getProperty(JDBC_DATASOURCE)).isEqualTo("INFO");
    }

    @Test
    void transactionAndDatasourceLoggersCanBeRaisedByEnvironmentVariable() {
        StandardEnvironment environment = environmentWith(Map.of(
                "LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_TRANSACTION_INTERCEPTOR", "DEBUG",
                "LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_TRANSACTION_SUPPORT", "TRACE",
                "LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_JDBC_DATASOURCE", "WARN"));

        assertThat(environment.getProperty(TX_INTERCEPTOR)).isEqualTo("DEBUG");
        assertThat(environment.getProperty(TX_SUPPORT)).isEqualTo("TRACE");
        assertThat(environment.getProperty(JDBC_DATASOURCE)).isEqualTo("WARN");
    }
}
