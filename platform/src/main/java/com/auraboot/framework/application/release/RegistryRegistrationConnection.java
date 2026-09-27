package com.auraboot.framework.application.release;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

/** Private registration pool; never contributes a primary DataSource or transaction-manager bean. */
final class RegistryRegistrationConnection {
    private RegistryRegistrationConnection() {}

    static HikariDataSource open(Environment environment) {
        String prefix = "aura.registry.registration.";
        String url = required(environment, prefix + "jdbc-url");
        String username = required(environment, prefix + "username");
        String passwordFile = required(environment, prefix + "password-file");
        if (!url.startsWith("jdbc:postgresql://") || username.equals(environment.getProperty("spring.datasource.username"))) {
            throw new IllegalArgumentException("Registry requires PostgreSQL and an account distinct from the runtime account");
        }
        String password;
        try {
            password = Files.readString(Path.of(passwordFile)).replaceFirst("\\r?\\n$", "");
        } catch (IOException failure) {
            throw new IllegalArgumentException("Registry password file cannot be read");
        }
        if (password.isEmpty()) throw new IllegalArgumentException("Registry password file is empty");
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName("ApplicationRegistryRegistration");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        var pool = new HikariDataSource(config);
        try {
            var jdbc = new JdbcTemplate(pool);
            Boolean safe = jdbc.queryForObject("""
                    SELECT current_user = ? AND NOT r.rolsuper AND NOT r.rolcreaterole AND NOT r.rolcreatedb
                      AND NOT r.rolreplication AND NOT r.rolbypassrls
                      AND NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member=r.oid)
                      AND NOT has_schema_privilege(current_user, current_schema(), 'CREATE')
                    FROM pg_roles r WHERE r.rolname=current_user
                    """, Boolean.class, username);
            if (!Boolean.TRUE.equals(safe)) throw new IllegalStateException("Registry account violates least-privilege preconditions");
            for (String table : java.util.List.of("ab_application", "ab_application_release", "ab_application_release_component", "ab_platform_release_registry")) {
                Boolean allowed = jdbc.queryForObject("""
                        SELECT has_table_privilege(current_user, ?, 'SELECT')
                          AND has_table_privilege(current_user, ?, 'INSERT')
                          AND NOT has_table_privilege(current_user, ?, 'UPDATE')
                          AND NOT has_table_privilege(current_user, ?, 'DELETE')
                          AND NOT has_table_privilege(current_user, ?, 'TRUNCATE')
                        """, Boolean.class, table, table, table, table, table);
                if (!Boolean.TRUE.equals(allowed)) throw new IllegalStateException("Registry table privileges are incomplete or excessive");
            }
            Boolean allocationAllowed = jdbc.queryForObject("""
                    SELECT has_column_privilege(current_user, 'ab_application', 'next_release_sequence', 'UPDATE')
                      AND has_sequence_privilege(current_user, pg_get_serial_sequence('ab_application','id'), 'USAGE')
                    """, Boolean.class);
            Boolean excessColumns = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                      JOIN pg_namespace n ON n.oid=c.relnamespace
                      WHERE n.nspname=current_schema() AND c.relname IN
                        ('ab_application','ab_application_release','ab_application_release_component','ab_platform_release_registry')
                      AND a.attnum>0 AND NOT a.attisdropped
                      AND NOT (c.relname='ab_application' AND a.attname='next_release_sequence')
                      AND has_column_privilege(current_user, c.oid, a.attnum, 'UPDATE'))
                    """, Boolean.class);
            if (!Boolean.TRUE.equals(allocationAllowed) || Boolean.TRUE.equals(excessColumns)) {
                throw new IllegalStateException("Registry column or sequence privileges are incomplete or excessive");
            }
            return pool;
        } catch (RuntimeException failure) {
            pool.close();
            throw failure;
        }
    }

    private static String required(Environment environment, String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing registry configuration: " + key);
        return value;
    }
}
