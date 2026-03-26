package com.auraboot.framework.application.bootstrap.seeder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SystemFieldSeeder {
    private final JdbcTemplate jdbcTemplate;

    public void seed() {
        String sql = """
            INSERT INTO ab_meta_field (pid, tenant_id, code, data_type, status, is_current, version, extension, created_at, updated_at)
            VALUES (?, 1, ?, ?, 'published', true, 1, ?::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (tenant_id, code, version) DO NOTHING
            """;

        Object[][] fields = {
            {"sys_field_id", "id", "bigint", "{\"extension\": {\"displayName\": \"ID\", \"description\": \"Primary key\", \"isPrimaryKey\": true}}"},
            {"sys_field_pid", "pid", "string", "{\"extension\": {\"displayName\": \"PID\", \"description\": \"Public identifier\"}}"},
            {"sys_field_created_at", "created_at", "datetime", "{\"extension\": {\"displayName\": \"Created At\", \"description\": \"Record creation time\"}}"},
            {"sys_field_updated_at", "updated_at", "datetime", "{\"extension\": {\"displayName\": \"Updated At\", \"description\": \"Record update time\"}}"},
        };

        int count = 0;
        for (Object[] f : fields) {
            count += jdbcTemplate.update(sql, f[0], f[1], f[2], f[3]);
        }
        log.info("SystemFieldSeeder: seeded {} system fields (skipped {} existing)", count, fields.length - count);
    }
}
