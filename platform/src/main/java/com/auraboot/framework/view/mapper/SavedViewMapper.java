package com.auraboot.framework.view.mapper;

import com.auraboot.framework.view.entity.SavedView;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * SavedView Mapper interface
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Mapper
public interface SavedViewMapper extends BaseMapper<SavedView> {

    // Result mapping ID for reuse
    String RESULT_MAP_ID = "SavedViewResultMap";

    /**
     * Find view by PID
     */
    @Results(id = RESULT_MAP_ID, value = {
            @Result(property = "id", column = "id"),
            @Result(property = "pid", column = "pid"),
            @Result(property = "tenantId", column = "tenant_id"),
            @Result(property = "name", column = "name"),
            @Result(property = "description", column = "description"),
            @Result(property = "modelCode", column = "model_code"),
            @Result(property = "pageKey", column = "page_key"),
            @Result(property = "scope", column = "scope"),
            @Result(property = "viewType", column = "view_type"),
            @Result(property = "ownerId", column = "owner_id"),
            @Result(property = "teamId", column = "team_id"),
            @Result(property = "viewConfig", column = "view_config",
                    typeHandler = com.auraboot.framework.view.typehandler.ViewConfigTypeHandler.class),
            @Result(property = "allowFullModel", column = "allow_full_model"),
            @Result(property = "isDefault", column = "is_default"),
            @Result(property = "sortOrder", column = "sort_order"),
            @Result(property = "deletedFlag", column = "deleted_flag"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "updatedAt", column = "updated_at"),
            @Result(property = "createdBy", column = "created_by"),
            @Result(property = "updatedBy", column = "updated_by")
    })
    @Select("SELECT * FROM ab_saved_view WHERE pid = #{pid} AND deleted_flag = false")
    SavedView findByPid(@Param("pid") String pid);

    /**
     * Find personal views for a user on a specific model/page
     */
    @ResultMap(RESULT_MAP_ID)
    @Select("""
        <script>
        SELECT * FROM ab_saved_view
        WHERE model_code = #{modelCode}
          AND scope = 'personal'
          AND owner_id = #{ownerId}
          AND deleted_flag = false
        <if test="pageKey != null">
          AND page_key = #{pageKey}
        </if>
        <if test="pageKey == null">
          AND page_key IS NULL
        </if>
        ORDER BY sort_order, created_at DESC
        </script>
        """)
    List<SavedView> findPersonalViews(
            @Param("modelCode") String modelCode,
            @Param("pageKey") String pageKey,
            @Param("ownerId") String ownerId);

    /**
     * Find team views for a specific model/page
     */
    @ResultMap(RESULT_MAP_ID)
    @Select("""
        <script>
        SELECT * FROM ab_saved_view
        WHERE model_code = #{modelCode}
          AND scope = 'team'
          AND team_id = #{teamId}
          AND deleted_flag = false
        <if test="pageKey != null">
          AND page_key = #{pageKey}
        </if>
        <if test="pageKey == null">
          AND page_key IS NULL
        </if>
        ORDER BY sort_order, created_at DESC
        </script>
        """)
    List<SavedView> findTeamViews(
            @Param("modelCode") String modelCode,
            @Param("pageKey") String pageKey,
            @Param("teamId") String teamId);

    /**
     * Find global views for a specific model/page
     */
    @ResultMap(RESULT_MAP_ID)
    @Select("""
        <script>
        SELECT * FROM ab_saved_view
        WHERE model_code = #{modelCode}
          AND scope = 'global'
          AND deleted_flag = false
        <if test="pageKey != null">
          AND page_key = #{pageKey}
        </if>
        <if test="pageKey == null">
          AND page_key IS NULL
        </if>
        ORDER BY sort_order, created_at DESC
        </script>
        """)
    List<SavedView> findGlobalViews(
            @Param("modelCode") String modelCode,
            @Param("pageKey") String pageKey);

    /**
     * Find all accessible views for a user (personal + team + global)
     */
    @ResultMap(RESULT_MAP_ID)
    @Select("""
        <script>
        SELECT * FROM ab_saved_view
        WHERE model_code = #{modelCode}
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
        <if test="pageKey != null">
          AND page_key = #{pageKey}
        </if>
        <if test="pageKey == null">
          AND page_key IS NULL
        </if>
        ORDER BY
            CASE scope
                WHEN 'personal' THEN 1
                WHEN 'team' THEN 2
                WHEN 'global' THEN 3
            END,
            sort_order, created_at DESC
        </script>
        """)
    List<SavedView> findAccessibleViews(
            @Param("modelCode") String modelCode,
            @Param("pageKey") String pageKey,
            @Param("ownerId") String ownerId,
            @Param("teamIds") List<String> teamIds);

    /**
     * Find default view for a user
     */
    @ResultMap(RESULT_MAP_ID)
    @Select("""
        <script>
        SELECT * FROM ab_saved_view
        WHERE model_code = #{modelCode}
          AND is_default = true
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
        <if test="pageKey != null">
          AND page_key = #{pageKey}
        </if>
        <if test="pageKey == null">
          AND page_key IS NULL
        </if>
        ORDER BY
            CASE scope
                WHEN 'personal' THEN 1
                WHEN 'team' THEN 2
                WHEN 'global' THEN 3
            END
        LIMIT 1
        </script>
        """)
    SavedView findDefaultView(
            @Param("modelCode") String modelCode,
            @Param("pageKey") String pageKey,
            @Param("ownerId") String ownerId,
            @Param("teamIds") List<String> teamIds);

    /**
     * Clear default flag for all views of a model/page/user combination
     */
    @Update("""
        <script>
        UPDATE ab_saved_view
        SET is_default = false, updated_at = NOW()
        WHERE model_code = #{modelCode}
          AND scope = 'personal'
          AND owner_id = #{ownerId}
          AND deleted_flag = false
          <if test="pageKey != null">
            AND page_key = #{pageKey}
          </if>
          <if test="pageKey == null">
            AND page_key IS NULL
          </if>
        </script>
        """)
    int clearPersonalDefaultFlag(
            @Param("modelCode") String modelCode,
            @Param("pageKey") String pageKey,
            @Param("ownerId") String ownerId);

    /**
     * Clear default flag for all TEAM views of a model/page/team combination
     */
    @Update("""
        <script>
        UPDATE ab_saved_view
        SET is_default = false, updated_at = NOW()
        WHERE model_code = #{modelCode}
          AND scope = 'team'
          AND team_id = #{teamId}
          AND is_default = true
          AND deleted_flag = false
          <if test="pageKey != null">
            AND page_key = #{pageKey}
          </if>
          <if test="pageKey == null">
            AND page_key IS NULL
          </if>
        </script>
        """)
    int clearTeamDefaultFlag(
            @Param("modelCode") String modelCode,
            @Param("pageKey") String pageKey,
            @Param("teamId") String teamId);

    /**
     * Clear default flag for all GLOBAL views of a model/page combination
     */
    @Update("""
        <script>
        UPDATE ab_saved_view
        SET is_default = false, updated_at = NOW()
        WHERE model_code = #{modelCode}
          AND scope = 'global'
          AND is_default = true
          AND deleted_flag = false
          <if test="pageKey != null">
            AND page_key = #{pageKey}
          </if>
          <if test="pageKey == null">
            AND page_key IS NULL
          </if>
        </script>
        """)
    int clearGlobalDefaultFlag(
            @Param("modelCode") String modelCode,
            @Param("pageKey") String pageKey);

    /**
     * Check if view name exists for user
     */
    @Select("""
        <script>
        SELECT COUNT(*) FROM ab_saved_view
        WHERE model_code = #{modelCode}
          AND name = #{name}
          AND scope = 'personal'
          AND owner_id = #{ownerId}
          AND deleted_flag = false
        <if test="excludePid != null">
          AND pid != #{excludePid}
        </if>
        <if test="pageKey != null">
          AND page_key = #{pageKey}
        </if>
        <if test="pageKey == null">
          AND page_key IS NULL
        </if>
        </script>
        """)
    int countByNameForUser(
            @Param("modelCode") String modelCode,
            @Param("pageKey") String pageKey,
            @Param("name") String name,
            @Param("ownerId") String ownerId,
            @Param("excludePid") String excludePid);

    /**
     * Insert with JSONB handling
     */
    @Insert("""
        INSERT INTO ab_saved_view (
            pid, tenant_id, name, description, model_code, page_key,
            scope, view_type, owner_id, team_id, view_config, allow_full_model,
            is_default, sort_order, deleted_flag, created_at, updated_at,
            created_by, updated_by
        ) VALUES (
            #{pid}, #{tenantId}, #{name}, #{description}, #{modelCode}, #{pageKey},
            #{scope}, #{viewType}, #{ownerId}, #{teamId},
            #{viewConfig, typeHandler=com.auraboot.framework.view.typehandler.ViewConfigTypeHandler},
            #{allowFullModel}, #{isDefault}, #{sortOrder}, #{deletedFlag},
            #{createdAt}, #{updatedAt}, #{createdBy}, #{updatedBy}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSavedView(SavedView savedView);

    /**
     * Update with JSONB handling
     */
    @Update("""
        UPDATE ab_saved_view SET
            name = #{name},
            description = #{description},
            scope = #{scope},
            team_id = #{teamId},
            view_config = #{viewConfig, typeHandler=com.auraboot.framework.view.typehandler.ViewConfigTypeHandler},
            allow_full_model = #{allowFullModel},
            is_default = #{isDefault},
            sort_order = #{sortOrder},
            updated_at = #{updatedAt},
            updated_by = #{updatedBy}
        WHERE pid = #{pid} AND deleted_flag = false
        """)
    int updateSavedView(SavedView savedView);

    /**
     * Update only view_config column using explicit JSONB cast (bypasses ORM type handling)
     */
    @Update("UPDATE ab_saved_view SET view_config = CAST(#{viewConfig} AS jsonb), updated_at = NOW() WHERE pid = #{pid} AND deleted_flag = false")
    int updateViewConfigJson(@Param("pid") String pid, @Param("viewConfig") String viewConfig);

    /**
     * Select view_config as raw JSON text (bypasses ViewConfigTypeHandler deserialization).
     * Used by ViewShareService to preserve __share metadata that is not mapped in ViewConfig class.
     */
    @Select("SELECT view_config::text FROM ab_saved_view WHERE pid = #{pid} AND deleted_flag = false")
    String selectRawViewConfigJson(@Param("pid") String pid);

    /**
     * Find a view's metadata + raw config by share token.
     * Returns a plain Map to avoid ViewConfigTypeHandler / MetaContext dependency.
     * Used for public share link access where no tenant context is available.
     * InterceptorIgnore bypasses TenantLineInterceptor (which requires MetaContext).
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT pid, name, model_code AS modelcode, view_type AS viewtype, " +
            "view_config::text AS viewconfigraw " +
            "FROM ab_saved_view " +
            "WHERE deleted_flag = false " +
            "AND view_config->'__share'->>'token' = #{token} " +
            "LIMIT 1")
    java.util.Map<String, Object> findRawViewByShareToken(@Param("token") String token);

    /**
     * Find SavedView by share token embedded in view_config.__share.token.
     * Bypasses TenantLineInterceptor via InterceptorIgnore.
     */
    @ResultMap(RESULT_MAP_ID)
    @Select("SELECT * FROM ab_saved_view " +
            "WHERE deleted_flag = false " +
            "AND view_config->'__share'->>'token' = #{token} " +
            "LIMIT 1")
    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true")
    List<SavedView> findViewByShareToken(@Param("token") String token);

    /**
     * Select raw view_config JSON text by share token (no TypeHandler).
     */
    @Select("SELECT view_config::text FROM ab_saved_view " +
            "WHERE deleted_flag = false " +
            "AND view_config->'__share'->>'token' = #{token} " +
            "LIMIT 1")
    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true")
    String selectRawViewConfigByShareToken(@Param("token") String token);
}
