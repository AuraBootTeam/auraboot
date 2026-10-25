package com.auraboot.framework.meta.mapper;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DynamicSqlProviderTargetLockTest {
    @Test void identityCasLockKeepsItsBoundPredicateAndTheGenericSelectGateRemainsReadOnly() {
        String sql="SELECT pid FROM mt_task WHERE tenant_id = #{params.tenantId} AND (pid = #{params.id0}"
                +" AND status IS NOT DISTINCT FROM #{params.expected0}) AND (created_by = 20) ORDER BY pid FOR UPDATE";
        assertThat(DynamicSqlProvider.selectCompareAndSetLocks(Map.of("sql",sql,"params",Map.of("tenantId",41L))))
                .isEqualTo(sql);
        assertThatThrownBy(()->DynamicSqlProvider.selectByQuery(Map.of("sql",sql))).hasMessageContaining("UPDATE");
    }

    @Test void identityCasLockRejectsMissingScopeExpandedProjectionOrMutationSql() {
        String sql="SELECT pid FROM mt_task WHERE tenant_id = #{params.tenantId} AND (pid = #{params.id0}) ORDER BY pid FOR UPDATE";
        for(Object tenant:new Object[]{"41",0L,-1L})
            assertThatThrownBy(()->DynamicSqlProvider.selectCompareAndSetLocks(Map.of("sql",sql,"params",Map.of("tenantId",tenant))))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->DynamicSqlProvider.selectCompareAndSetLocks(Map.of("sql",sql,"params",Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        for(String changed:new String[]{sql.replace("tenant_id = #{params.tenantId}","tenant_id = 41"),
                sql.replace("SELECT pid FROM","SELECT pid, status FROM"),sql.replace("SELECT pid FROM","SELECT * FROM"),
                sql.replace("ORDER BY pid FOR UPDATE","FOR UPDATE"),sql+"; DELETE FROM mt_task",
                sql.replace("mt_task WHERE","mt_task; DELETE FROM mt_task WHERE"),
                sql.replace("AND (pid = #{params.id0})","AND (pid = #{params.id0}); UPDATE mt_task SET status = 1")})
            assertThatThrownBy(()->DynamicSqlProvider.selectCompareAndSetLocks(Map.of("sql",changed,"params",Map.of("tenantId",41L))))
                    .isInstanceOf(IllegalArgumentException.class);
    }

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
