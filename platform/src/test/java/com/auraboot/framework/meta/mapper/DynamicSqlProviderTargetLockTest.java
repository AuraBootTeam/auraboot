package com.auraboot.framework.meta.mapper;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DynamicSqlProviderTargetLockTest {
    @Test
    void locksOnlyTheBoundTenantAndPidWithoutInterpolatingValues() {
        String sql = DynamicSqlProvider.selectTargetVersionForUpdate(Map.of(
                "tableName", "mt_task", "primaryKeyColumn", "pid",
                "tenantId", 41L, "targetRecordPid", "x' OR 1=1 --"));
        assertThat(sql).isEqualTo("SELECT row_version FROM mt_task WHERE tenant_id = #{tenantId}"
                + " AND pid = #{targetRecordPid} FOR UPDATE");
        assertThatThrownBy(() -> DynamicSqlProvider.selectByQuery(Map.of("sql", sql)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("UPDATE");
    }

    @Test
    void rejectsIdentifierInjectionAndMissingTenantScope() {
        for (String field : new String[]{"tableName", "primaryKeyColumn"}) {
            var params = new HashMap<String, Object>(Map.of("tableName", "mt_task",
                    "primaryKeyColumn", "pid", "tenantId", 41L, "targetRecordPid", "TASK"));
            params.put(field, "pid; DELETE FROM mt_task");
            assertThatThrownBy(() -> DynamicSqlProvider.selectTargetVersionForUpdate(params))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> DynamicSqlProvider.selectTargetVersionForUpdate(Map.of(
                "tableName", "mt_task", "primaryKeyColumn", "pid", "targetRecordPid", "TASK")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenantId");
    }
}
