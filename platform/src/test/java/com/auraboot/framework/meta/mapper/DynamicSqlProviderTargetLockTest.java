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
    @Test void recordOwnershipLocksBindValuesAndKeepTheGenericSelectGateStrict() {
        String sql=DynamicSqlProvider.selectRecordWriterTargetsForUpdate(Map.of("tableName","mt_bid","markerColumn","managed_flag",
            "markerAlias","managed","conditions",Map.of("tenant_id",41L,"id","x' OR 1=1 --","deleted_flag",false)));
        assertThat(sql).contains("managed_flag AS managed","tenant_id = #{conditions.tenant_id}",
            "id::text = #{conditions.id}::text OR pid = #{conditions.id}::text","FOR UPDATE").doesNotContain("x' OR 1=1");
        assertThatThrownBy(()->DynamicSqlProvider.selectByQuery(Map.of("sql",sql))).hasMessageContaining("UPDATE");
    }
    @Test void ownershipLockRejectsMissingTenantAndUnsafeIdentifiers() {
        var params=new HashMap<String,Object>(Map.of("tableName","mt_bid","markerColumn","managed_flag",
            "markerAlias","managed","conditions",Map.of("id","bid-a")));
        assertThatThrownBy(()->DynamicSqlProvider.selectRecordWriterTargetsForUpdate(params)).hasMessageContaining("tenant_id");
        params.put("conditions",Map.of("tenant_id",41L));
        for(String name:new String[]{"tableName","markerColumn","markerAlias"}) {
            var bad=new HashMap<>(params);bad.put(name,"managed; DELETE FROM mt_bid");
            assertThatThrownBy(()->DynamicSqlProvider.selectRecordWriterTargetsForUpdate(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        params.put("conditions",Map.of("tenant_id",41L,"pid OR TRUE",1));
        assertThatThrownBy(()->DynamicSqlProvider.selectRecordWriterTargetsForUpdate(params)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void junctionLockRequiresTenantAndDoesNotInterpolateSourceIdentity() {
        var params=new HashMap<String,Object>(Map.of("tableName","mt_links","sourceColumn","parent_pid",
            "targetColumn","bid_pid","tenantId",41L,"sourceIdentity","x' OR TRUE --"));
        String sql=DynamicSqlProvider.selectRelationTargetsForUpdate(params);
        assertThat(sql).contains("parent_pid = #{sourceIdentity}","tenant_id = #{tenantId}","ORDER BY bid_pid FOR UPDATE")
            .doesNotContain("x' OR TRUE");
        params.remove("tenantId");
        assertThatThrownBy(()->DynamicSqlProvider.selectRelationTargetsForUpdate(params)).hasMessageContaining("tenantId");
    }

}
