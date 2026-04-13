package com.auraboot.framework.dashboard.mapper;

import com.auraboot.framework.dashboard.entity.Dashboard;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Dashboard Mapper interface
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Mapper
public interface DashboardMapper extends BaseMapper<Dashboard> {

    String RESULT_MAP_ID = "DashboardResultMap";

    /**
     * Find dashboard by PID
     */
    @Results(id = RESULT_MAP_ID, value = {
            @Result(property = "id", column = "id"),
            @Result(property = "pid", column = "pid"),
            @Result(property = "tenantId", column = "tenant_id"),
            @Result(property = "code", column = "code"),
            @Result(property = "title", column = "title"),
            @Result(property = "description", column = "description"),
            @Result(property = "scope", column = "scope"),
            @Result(property = "ownerId", column = "owner_id"),
            @Result(property = "teamId", column = "team_id"),
            @Result(property = "layoutConfig", column = "layout_config",
                    typeHandler = com.auraboot.framework.application.typehandler.JsonNodeTypeHandler.class),
            @Result(property = "widgets", column = "widgets",
                    typeHandler = com.auraboot.framework.application.typehandler.JsonNodeTypeHandler.class),
            @Result(property = "status", column = "status"),
            @Result(property = "isDefault", column = "is_default"),
            @Result(property = "sortOrder", column = "sort_order"),
            @Result(property = "extension", column = "extension",
                    typeHandler = com.auraboot.framework.application.typehandler.JsonNodeTypeHandler.class),
            @Result(property = "deletedFlag", column = "deleted_flag"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "updatedAt", column = "updated_at"),
            @Result(property = "createdBy", column = "created_by"),
            @Result(property = "updatedBy", column = "updated_by")
    })
    @Select("SELECT * FROM ab_dashboard WHERE pid = #{pid} AND deleted_flag = false")
    Dashboard findByPid(@Param("pid") String pid);

    /**
     * Find dashboard by code within tenant
     */
    @ResultMap(RESULT_MAP_ID)
    @Select("""
        SELECT * FROM ab_dashboard
        WHERE tenant_id = #{tenantId}
          AND code = #{code}
          AND deleted_flag = false
        """)
    Dashboard findByCode(@Param("tenantId") Long tenantId, @Param("code") String code);

    /**
     * Find personal dashboards for a user
     */
    @ResultMap(RESULT_MAP_ID)
    @Select("""
        SELECT * FROM ab_dashboard
        WHERE tenant_id = #{tenantId}
          AND scope = 'personal'
          AND owner_id = #{ownerId}
          AND deleted_flag = false
        ORDER BY sort_order, updated_at DESC
        """)
    List<Dashboard> findPersonalDashboards(
            @Param("tenantId") Long tenantId,
            @Param("ownerId") String ownerId);

    /**
     * Find global dashboards
     */
    @ResultMap(RESULT_MAP_ID)
    @Select("""
        SELECT * FROM ab_dashboard
        WHERE tenant_id = #{tenantId}
          AND scope = 'global'
          AND deleted_flag = false
        ORDER BY sort_order, updated_at DESC
        """)
    List<Dashboard> findGlobalDashboards(@Param("tenantId") Long tenantId);

    /**
     * Find all accessible dashboards for a user (personal + team + global)
     */
    @ResultMap(RESULT_MAP_ID)
    @Select("""
        <script>
        SELECT * FROM ab_dashboard
        WHERE tenant_id = #{tenantId}
          AND deleted_flag = false
          AND (
            (scope = 'personal' AND owner_id = #{ownerId})
            OR (scope = 'team' AND created_by = #{ownerId})
            <if test="teamIds != null and teamIds.size() > 0">
            OR (scope = 'team' AND team_id IN
                <foreach collection="teamIds" item="teamId" open="(" separator="," close=")">
                    #{teamId}
                </foreach>
            )
            </if>
            OR scope = 'global'
          )
        <if test="status != null">
          AND status = #{status}
        </if>
        <if test="title != null and title != ''">
          AND title ILIKE '%' || #{title} || '%'
        </if>
        <if test="scope != null and scope != ''">
          AND scope = #{scope}
        </if>
        ORDER BY
            CASE scope
                WHEN 'personal' THEN 1
                WHEN 'team' THEN 2
                WHEN 'global' THEN 3
            END,
            sort_order, updated_at DESC
        </script>
        """)
    List<Dashboard> findAccessibleDashboards(
            @Param("tenantId") Long tenantId,
            @Param("ownerId") String ownerId,
            @Param("teamIds") List<String> teamIds,
            @Param("status") String status,
            @Param("title") String title,
            @Param("scope") String scope);

    /**
     * Find default dashboard for a user
     */
    @ResultMap(RESULT_MAP_ID)
    @Select("""
        <script>
        SELECT * FROM ab_dashboard
        WHERE tenant_id = #{tenantId}
          AND is_default = true
          AND status = 'published'
          AND deleted_flag = false
          AND (
            (scope = 'personal' AND owner_id = #{ownerId})
            OR (scope = 'team' AND created_by = #{ownerId})
            <if test="teamIds != null and teamIds.size() > 0">
            OR (scope = 'team' AND team_id IN
                <foreach collection="teamIds" item="teamId" open="(" separator="," close=")">
                    #{teamId}
                </foreach>
            )
            </if>
            OR scope = 'global'
          )
        ORDER BY
            CASE scope
                WHEN 'personal' THEN 1
                WHEN 'team' THEN 2
                WHEN 'global' THEN 3
            END
        LIMIT 1
        </script>
        """)
    Dashboard findDefaultDashboard(
            @Param("tenantId") Long tenantId,
            @Param("ownerId") String ownerId,
            @Param("teamIds") List<String> teamIds);

    /**
     * Find workbench dashboard for a specific user.
     */
    @ResultMap(RESULT_MAP_ID)
    @Select("""
        SELECT * FROM ab_dashboard
        WHERE tenant_id = #{tenantId}
          AND owner_id = #{ownerPid}
          AND scope = 'workbench'
          AND (deleted_flag = FALSE OR deleted_flag IS NULL)
        LIMIT 1
        """)
    Dashboard findWorkbench(@Param("tenantId") Long tenantId, @Param("ownerPid") String ownerPid);

    /**
     * Clear default flag for personal dashboards of a user
     */
    @Update("""
        UPDATE ab_dashboard
        SET is_default = false, updated_at = NOW()
        WHERE tenant_id = #{tenantId}
          AND scope = 'personal'
          AND owner_id = #{ownerId}
          AND deleted_flag = false
        """)
    int clearPersonalDefaultFlag(
            @Param("tenantId") Long tenantId,
            @Param("ownerId") String ownerId);

    /**
     * Check if dashboard code exists within tenant
     */
    @Select("""
        <script>
        SELECT COUNT(*) FROM ab_dashboard
        WHERE tenant_id = #{tenantId}
          AND code = #{code}
          AND deleted_flag = false
        <if test="excludePid != null">
          AND pid != #{excludePid}
        </if>
        </script>
        """)
    int countByCode(
            @Param("tenantId") Long tenantId,
            @Param("code") String code,
            @Param("excludePid") String excludePid);

    /**
     * Insert with JSONB handling
     */
    @Insert("""
        INSERT INTO ab_dashboard (
            pid, tenant_id, code, title, description, scope, owner_id, team_id,
            layout_config, widgets, status, is_default, sort_order, extension,
            deleted_flag, created_at, updated_at, created_by, updated_by
        ) VALUES (
            #{pid}, #{tenantId}, #{code}, #{title}, #{description}, #{scope}, #{ownerId}, #{teamId},
            #{layoutConfig, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler}::jsonb,
            #{widgets, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler}::jsonb,
            #{status}, #{isDefault}, #{sortOrder},
            #{extension, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler}::jsonb,
            #{deletedFlag}, #{createdAt}, #{updatedAt}, #{createdBy}, #{updatedBy}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertDashboard(Dashboard dashboard);

    /**
     * Update with JSONB handling
     */
    @Update("""
        UPDATE ab_dashboard SET
            title = #{title},
            description = #{description},
            scope = #{scope},
            team_id = #{teamId},
            layout_config = #{layoutConfig, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler}::jsonb,
            widgets = #{widgets, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler}::jsonb,
            status = #{status},
            is_default = #{isDefault},
            sort_order = #{sortOrder},
            extension = #{extension, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler}::jsonb,
            updated_at = #{updatedAt},
            updated_by = #{updatedBy}
        WHERE pid = #{pid} AND deleted_flag = false
        """)
    int updateDashboard(Dashboard dashboard);

    /**
     * Update only the extension JSONB field for a dashboard
     */
    @Update("""
        UPDATE ab_dashboard
        SET extension = #{extension, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler}::jsonb,
            updated_at = NOW()
        WHERE pid = #{pid} AND deleted_flag = false
        """)
    void updateExtension(@Param("pid") String pid, @Param("extension") JsonNode extension);
}
