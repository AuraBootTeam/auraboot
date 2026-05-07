package com.auraboot.framework.aurabot.skill.mapper;

import com.auraboot.framework.application.typehandler.JsonNodeTypeHandler;
import com.auraboot.framework.aurabot.skill.entity.SkillRun;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

/**
 * MyBatis mapper for {@code ab_aurabot_skill_run}.
 *
 * <p>All read paths explicitly carry
 * {@code AND (deleted_flag = FALSE OR deleted_flag IS NULL)} per AGENTS.md
 * "数据库与数据" red-line for native-SQL mappers.
 */
@Mapper
public interface SkillRunMapper {

    String RESULT_MAP_ID = "SkillRunResultMap";

    @Insert("""
            INSERT INTO ab_aurabot_skill_run (
                pid, tenant_id, skill_name, params_json, before_snapshot, after_snapshot,
                idempotency_key, undo_token, batch_id,
                status, risk_level, created_by, created_at, undone_at, deleted_flag
            ) VALUES (
                #{pid}, #{tenantId}, #{skillName},
                #{paramsJson, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler}::jsonb,
                #{beforeSnapshot, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler}::jsonb,
                #{afterSnapshot, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler}::jsonb,
                #{idempotencyKey}, #{undoToken}, #{batchId},
                #{status}, #{riskLevel}, #{createdBy},
                #{createdAt}, #{undoneAt},
                COALESCE(#{deletedFlag}, FALSE)
            )
            """)
    int insert(SkillRun run);

    /**
     * Find a live run by tenant + skill + idempotencyKey, restricted to a
     * caller-supplied lower-bound timestamp (the 5-min window per Spec §6).
     * Returning the most recent live row guards against duplicate inserts
     * predating an explicit soft-delete.
     *
     * <p>Hosts the canonical {@code @Results} definition for this mapper —
     * other read methods reference it via {@code @ResultMap(RESULT_MAP_ID)}.
     * Keeping the first read method as the {@code @Results} owner avoids a
     * MyBatis bootstrap race where {@code @ResultMap} resolves before its
     * target {@code @Results} is registered.
     */
    @Results(id = RESULT_MAP_ID, value = {
            @Result(property = "pid",             column = "pid"),
            @Result(property = "tenantId",        column = "tenant_id"),
            @Result(property = "skillName",       column = "skill_name"),
            @Result(property = "paramsJson",      column = "params_json",
                    typeHandler = JsonNodeTypeHandler.class),
            @Result(property = "beforeSnapshot",  column = "before_snapshot",
                    typeHandler = JsonNodeTypeHandler.class),
            @Result(property = "afterSnapshot",   column = "after_snapshot",
                    typeHandler = JsonNodeTypeHandler.class),
            @Result(property = "idempotencyKey",  column = "idempotency_key"),
            @Result(property = "undoToken",       column = "undo_token"),
            @Result(property = "batchId",         column = "batch_id"),
            @Result(property = "status",          column = "status"),
            @Result(property = "riskLevel",       column = "risk_level"),
            @Result(property = "createdBy",       column = "created_by"),
            @Result(property = "createdAt",       column = "created_at"),
            @Result(property = "undoneAt",        column = "undone_at"),
            @Result(property = "deletedFlag",     column = "deleted_flag")
    })
    @Select("""
            SELECT *
              FROM ab_aurabot_skill_run
             WHERE tenant_id = #{tenantId}
               AND skill_name = #{skillName}
               AND idempotency_key = #{idempotencyKey}
               AND created_at >= #{sinceTs}
               AND (deleted_flag = FALSE OR deleted_flag IS NULL)
             ORDER BY created_at DESC
             LIMIT 1
            """)
    SkillRun findByIdempotency(@Param("tenantId") Long tenantId,
                               @Param("skillName") String skillName,
                               @Param("idempotencyKey") String idempotencyKey,
                               @Param("sinceTs") Instant sinceTs);

    @ResultMap(RESULT_MAP_ID)
    @Select("""
            SELECT *
              FROM ab_aurabot_skill_run
             WHERE undo_token = #{undoToken}
               AND (deleted_flag = FALSE OR deleted_flag IS NULL)
             LIMIT 1
            """)
    SkillRun findByUndoToken(@Param("undoToken") String undoToken);

    @ResultMap(RESULT_MAP_ID)
    @Select("""
            SELECT *
              FROM ab_aurabot_skill_run
             WHERE tenant_id = #{tenantId}
               AND batch_id = #{batchId}
               AND (deleted_flag = FALSE OR deleted_flag IS NULL)
             ORDER BY created_at DESC
            """)
    List<SkillRun> findByBatchId(@Param("tenantId") Long tenantId,
                                 @Param("batchId") String batchId);

    /**
     * Flip a run from {@code success} to {@code undone} and stamp {@code undone_at}.
     * Returns affected row count; the caller is expected to assert == 1.
     */
    @Update("""
            UPDATE ab_aurabot_skill_run
               SET status = #{status},
                   undone_at = #{undoneAt}
             WHERE pid = #{pid}
               AND (deleted_flag = FALSE OR deleted_flag IS NULL)
            """)
    int markUndone(@Param("pid") String pid,
                   @Param("status") String status,
                   @Param("undoneAt") Instant undoneAt);

    @Select("""
            SELECT COUNT(*)
              FROM ab_aurabot_skill_run
             WHERE tenant_id = #{tenantId}
               AND created_at >= #{sinceTs}
               AND (deleted_flag = FALSE OR deleted_flag IS NULL)
            """)
    long countByTenantSinceTs(@Param("tenantId") Long tenantId,
                              @Param("sinceTs") Instant sinceTs);
}
