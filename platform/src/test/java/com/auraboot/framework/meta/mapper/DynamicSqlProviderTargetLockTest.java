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
        for (boolean softDelete : new boolean[]{false, true}) {
            String children = DynamicSqlProvider.selectRelationChildrenForUpdate(Map.of(
                    "tableName", "mt_task", "parentColumn", "parent_pid", "tenantId", 41L,
                    "parentPid", "x' OR 1=1 --", "softDelete", softDelete));
            assertThat(children).isEqualTo("SELECT pid FROM mt_task WHERE tenant_id = #{tenantId}"
                    + " AND parent_pid = #{parentPid}"
                    + (softDelete ? " AND deleted_flag = false" : "") + " ORDER BY pid FOR UPDATE");
        }
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
        var relation = new HashMap<String, Object>(Map.of("tableName", "mt_task", "parentColumn", "parent_pid",
                "tenantId", 41L, "parentPid", "TASK", "softDelete", true));
        for (String field : new String[]{"tableName", "parentColumn", "tenantId", "parentPid", "softDelete"}) {
            var invalid = new HashMap<>(relation);
            invalid.remove(field);
            assertThatThrownBy(() -> DynamicSqlProvider.selectRelationChildrenForUpdate(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String field : new String[]{"tableName", "parentColumn"}) {
            var invalid = new HashMap<>(relation);
            invalid.put(field, "pid; DELETE FROM mt_task");
            assertThatThrownBy(() -> DynamicSqlProvider.selectRelationChildrenForUpdate(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> DynamicSqlProvider.selectTargetVersionForUpdate(Map.of(
                "tableName", "mt_task", "primaryKeyColumn", "pid", "targetRecordPid", "TASK")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenantId");
    }
}
