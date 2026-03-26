package com.auraboot.framework.application.database.dialect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configures the correct {@link DatabaseDialect} bean based on:
 * <ol>
 *   <li>Explicit config: {@code aura.database.dialect=postgresql|mysql}</li>
 *   <li>Auto-detection from {@code spring.datasource.url}</li>
 * </ol>
 */
@Configuration
public class DialectAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DialectAutoConfiguration.class);

    @Value("${aura.database.dialect:}")
    private String explicitDialect;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @Bean
    public DatabaseDialect databaseDialect() {
        DatabaseType type = resolve();
        log.info("Database dialect resolved: {}", type);

        return switch (type) {
            case MYSQL -> new MySqlDialect();
            case POSTGRESQL -> new PostgresDialect();
        };
    }

    private DatabaseType resolve() {
        // 1. Explicit configuration takes precedence
        if (explicitDialect != null && !explicitDialect.isBlank()) {
            return switch (explicitDialect.trim().toLowerCase()) {
                case "mysql" -> DatabaseType.MYSQL;
                case "postgresql", "postgres", "pg" -> DatabaseType.POSTGRESQL;
                default -> throw new IllegalArgumentException(
                        "Unsupported dialect: " + explicitDialect
                                + ". Supported values: postgresql, mysql");
            };
        }

        // 2. Auto-detect from datasource URL
        if (datasourceUrl != null && !datasourceUrl.isBlank()) {
            String lower = datasourceUrl.toLowerCase();
            if (lower.startsWith("jdbc:mysql:") || lower.contains(":mysql:")) {
                return DatabaseType.MYSQL;
            }
            if (lower.startsWith("jdbc:postgresql:") || lower.contains(":postgresql:")) {
                return DatabaseType.POSTGRESQL;
            }
        }

        // 3. Default to PostgreSQL (current production database)
        log.warn("Cannot detect database type from URL '{}', defaulting to PostgreSQL", datasourceUrl);
        return DatabaseType.POSTGRESQL;
    }
}
