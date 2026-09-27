package com.auraboot.framework.application.release;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

/** Private shadow writer pool. Its account cannot activate or modify active bindings. */
final class ShadowBindingConnection {
    private ShadowBindingConnection() {}

    static HikariDataSource open(Environment environment) {
        String prefix = "aura.binding.shadow.";
        String url = required(environment, prefix + "jdbc-url");
        String username = required(environment, prefix + "username");
        String runtime = required(environment, "spring.datasource.username");
        if (!url.startsWith("jdbc:postgresql://") || username.equals(runtime)) {
            throw new IllegalArgumentException("Shadow binding requires PostgreSQL and a distinct control account");
        }
        String password;
        try { password = Files.readString(Path.of(required(environment, prefix + "password-file"))).replaceFirst("\\r?\\n$", ""); }
        catch (IOException failure) { throw new IllegalArgumentException("Shadow binding password file cannot be read"); }
        if (password.isEmpty()) throw new IllegalArgumentException("Shadow binding password file is empty");
        var config = new HikariConfig();
        config.setJdbcUrl(url); config.setUsername(username); config.setPassword(password);
        config.setPoolName("TenantApplicationShadowBinding");
        config.setMaximumPoolSize(4); config.setMinimumIdle(0); config.setConnectionTimeout(5000);
        var pool = new HikariDataSource(config);
        try {
            verify(new JdbcTemplate(pool), username);
            return pool;
        } catch (RuntimeException failure) { pool.close(); throw failure; }
    }

    private static void verify(JdbcTemplate jdbc, String username) {
        Boolean safe = jdbc.queryForObject("""
                SELECT current_user=? AND NOT r.rolsuper AND NOT r.rolcreaterole AND NOT r.rolcreatedb
                  AND NOT r.rolreplication AND NOT r.rolbypassrls
                  AND NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member=r.oid)
                  AND NOT has_schema_privilege(current_user,current_schema(),'CREATE')
                FROM pg_roles r WHERE r.rolname=current_user
                """, Boolean.class, username);
        require(Boolean.TRUE.equals(safe), "Shadow binding account violates least-privilege preconditions");
        for (String table : java.util.List.of("ab_application_release", "ab_tenant_application_binding", "ab_tenant_application_binding_history")) {
            Boolean allowed = jdbc.queryForObject("""
                    SELECT has_table_privilege(current_user,c.oid,'SELECT')
                      AND has_table_privilege(current_user,c.oid,'INSERT') = (c.relname='ab_tenant_application_binding_history')
                      AND NOT has_table_privilege(current_user,c.oid,'UPDATE,DELETE,TRUNCATE,TRIGGER,REFERENCES')
                      AND c.relowner <> (SELECT oid FROM pg_roles WHERE rolname=current_user)
                    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname=current_schema() AND c.relname=? AND c.relkind='r'
                    """, Boolean.class, table);
            require(Boolean.TRUE.equals(allowed), "Shadow binding table privileges are incomplete or excessive");
        }
        Boolean columns = jdbc.queryForObject("""
                SELECT bool_and(
                  has_column_privilege(current_user,c.oid,a.attnum,'INSERT') =
                    (c.relname='ab_tenant_application_binding_history' OR
                     (c.relname='ab_tenant_application_binding' AND a.attname IN ('tenant_id','application_id','current_release_id')))
                  AND has_column_privilege(current_user,c.oid,a.attnum,'UPDATE') =
                    (c.relname='ab_tenant_application_binding' AND a.attname IN ('current_release_id','binding_version'))
                  AND NOT has_column_privilege(current_user,c.oid,a.attnum,'REFERENCES'))
                FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname=current_schema() AND c.relname IN
                  ('ab_application_release','ab_tenant_application_binding','ab_tenant_application_binding_history')
                  AND a.attnum>0 AND NOT a.attisdropped
                """, Boolean.class);
        require(Boolean.TRUE.equals(columns), "Shadow binding column privileges are incomplete or excessive");
        Integer auditColumns = jdbc.queryForObject("""
                SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=current_schema()
                  AND c.relname='ab_tenant_application_binding_history' AND NOT a.attisdropped
                  AND a.attname IN ('business_actor','operation_id')
                """, Integer.class);
        require(auditColumns != null && auditColumns == 2, "Shadow binding operator audit migration is required");
        Boolean rls = jdbc.queryForObject("""
                SELECT c.relrowsecurity FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname=current_schema() AND c.relname='ab_tenant_application_binding'
                """, Boolean.class);
        Boolean policies = jdbc.queryForObject("""
                SELECT count(*)=3 AND bool_and(permissive='PERMISSIVE' AND CASE policyname
                  WHEN 'binding_control_read' THEN cmd='SELECT' AND current_user=ANY(roles::text[]) AND qual='true' AND with_check IS NULL
                  WHEN 'binding_shadow_insert' THEN cmd='INSERT' AND roles::text[]=ARRAY[current_user::text]
                    AND qual IS NULL AND with_check='(status = ''shadow''::text)'
                  WHEN 'binding_shadow_update' THEN cmd='UPDATE' AND roles::text[]=ARRAY[current_user::text]
                    AND qual='(status = ''shadow''::text)' AND with_check='(status = ''shadow''::text)'
                  ELSE false END)
                FROM pg_policies WHERE schemaname=current_schema() AND tablename='ab_tenant_application_binding'
                """, Boolean.class);
        require(Boolean.TRUE.equals(rls) && Boolean.TRUE.equals(policies), "Shadow binding row security is missing or differs from the approved policy");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static String required(Environment environment, String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing shadow binding configuration: " + key);
        return value;
    }
}
