package com.auraboot.framework.versioning.mapper;

import com.auraboot.framework.versioning.entity.DesignVersionHistory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for unified design version history.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Mapper
public interface DesignVersionHistoryMapper extends BaseMapper<DesignVersionHistory> {

    String RESULT_MAP_ID = "DesignVersionHistoryResultMap";

    @Results(id = RESULT_MAP_ID, value = {
            @Result(property = "id", column = "id"),
            @Result(property = "pid", column = "pid"),
            @Result(property = "tenantId", column = "tenant_id"),
            @Result(property = "resourceType", column = "resource_type"),
            @Result(property = "resourceId", column = "resource_id"),
            @Result(property = "version", column = "version"),
            @Result(property = "schemaSnapshot", column = "schema_snapshot",
                    typeHandler = com.auraboot.framework.application.typehandler.JsonNodeTypeHandler.class),
            @Result(property = "operation", column = "operation"),
            @Result(property = "operationBy", column = "operation_by"),
            @Result(property = "operationAt", column = "operation_at"),
            @Result(property = "description", column = "description"),
            @Result(property = "parentVersionId", column = "parent_version_id"),
            @Result(property = "metadata", column = "metadata",
                    typeHandler = com.auraboot.framework.application.typehandler.JsonNodeTypeHandler.class),
            @Result(property = "createdAt", column = "created_at")
    })
    @Select("""
        SELECT * FROM ab_design_version_history
        WHERE resource_type = #{resourceType}
          AND resource_id = #{resourceId}
          AND tenant_id = #{tenantId}
        ORDER BY operation_at DESC
        """)
    List<DesignVersionHistory> findByResource(
            @Param("tenantId") Long tenantId,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId);

    /**
     * Find version by PID
     */
    @ResultMap(RESULT_MAP_ID)
    @Select("""
        SELECT * FROM ab_design_version_history
        WHERE pid = #{pid}
          AND tenant_id = #{tenantId}
        """)
    DesignVersionHistory findByPid(
            @Param("tenantId") Long tenantId,
            @Param("pid") String pid);

    /**
     * Find latest N versions for a resource
     */
    @ResultMap(RESULT_MAP_ID)
    @Select("""
        SELECT * FROM ab_design_version_history
        WHERE resource_type = #{resourceType}
          AND resource_id = #{resourceId}
          AND tenant_id = #{tenantId}
        ORDER BY operation_at DESC
        LIMIT #{limit}
        """)
    List<DesignVersionHistory> findLatestVersions(
            @Param("tenantId") Long tenantId,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("limit") int limit);

    /**
     * Count versions for a resource
     */
    @Select("""
        SELECT COUNT(*) FROM ab_design_version_history
        WHERE resource_type = #{resourceType}
          AND resource_id = #{resourceId}
          AND tenant_id = #{tenantId}
        """)
    int countByResource(
            @Param("tenantId") Long tenantId,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId);

    /**
     * Insert with JSONB handling
     */
    @Insert("""
        INSERT INTO ab_design_version_history (
            pid, tenant_id, resource_type, resource_id, version,
            schema_snapshot, operation, operation_by, operation_at,
            description, parent_version_id, metadata, created_at
        ) VALUES (
            #{pid}, #{tenantId}, #{resourceType}, #{resourceId}, #{version},
            #{schemaSnapshot, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler}::jsonb,
            #{operation}, #{operationBy}, #{operationAt},
            #{description}, #{parentVersionId},
            #{metadata, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler}::jsonb,
            #{createdAt}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertVersion(DesignVersionHistory history);

    /**
     * Delete old versions, keeping the latest N for a resource
     */
    @Delete("""
        DELETE FROM ab_design_version_history
        WHERE resource_type = #{resourceType}
          AND resource_id = #{resourceId}
          AND tenant_id = #{tenantId}
          AND id NOT IN (
            SELECT id FROM ab_design_version_history
            WHERE resource_type = #{resourceType}
              AND resource_id = #{resourceId}
              AND tenant_id = #{tenantId}
            ORDER BY operation_at DESC
            LIMIT #{keepCount}
          )
        """)
    int deleteOldVersions(
            @Param("tenantId") Long tenantId,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("keepCount") int keepCount);
}
