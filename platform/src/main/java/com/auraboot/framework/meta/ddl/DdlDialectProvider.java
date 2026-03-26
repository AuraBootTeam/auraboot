package com.auraboot.framework.meta.ddl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Component
public class DdlDialectProvider {

    private static final Logger log = LoggerFactory.getLogger(DdlDialectProvider.class);

    private final DataSource dataSource;
    private volatile DdlDialect cachedDialect;

    public DdlDialectProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public DdlDialect getDialect() {
        if (cachedDialect != null) {
            return cachedDialect;
        }
        synchronized (this) {
            if (cachedDialect == null) {
                cachedDialect = detectDialect();
            }
        }
        return cachedDialect;
    }

    private DdlDialect detectDialect() {
        String productName = "unknown";
        try (Connection connection = dataSource.getConnection()) {
            productName = connection.getMetaData().getDatabaseProductName();
        } catch (Exception e) {
            log.warn("Failed to detect database dialect, defaulting to PostgreSQL", e);
            return new PostgresDdlDialect();
        }

        String name = productName.toLowerCase();
        if (name.contains("postgres")) {
            return new PostgresDdlDialect();
        }
        if (name.contains("mysql")) {
            return new MySqlDdlDialect();
        }

        log.warn("Unsupported database {}, defaulting to PostgreSQL dialect", productName);
        return new PostgresDdlDialect();
    }
}
