package com.auraboot.framework.application.database.dialect;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Static accessor for the active {@link DatabaseDialect}.
 * <p>
 * This allows non-Spring-managed code (e.g. type handlers, utility classes)
 * to access the configured dialect without dependency injection.
 */
@Component
public class DialectHolder {

    private final DatabaseDialect dialect;

    private static DatabaseDialect INSTANCE;

    public DialectHolder(DatabaseDialect dialect) {
        this.dialect = dialect;
    }

    @PostConstruct
    void init() {
        INSTANCE = dialect;
    }

    /**
     * Return the active dialect.  Falls back to {@link PostgresDialect} if
     * called before Spring context initialisation (e.g. during testing).
     */
    public static DatabaseDialect get() {
        return INSTANCE != null ? INSTANCE : new PostgresDialect();
    }

    /**
     * Check whether the active database is MySQL.
     */
    public static boolean isMySql() {
        return get().getType() == DatabaseType.MYSQL;
    }

    /**
     * Check whether the active database is PostgreSQL.
     */
    public static boolean isPostgres() {
        return get().getType() == DatabaseType.POSTGRESQL;
    }
}
