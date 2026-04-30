package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.dto.PageSchemaSyncVersionDTO;
import com.auraboot.framework.meta.entity.PageSchema;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

/**
 * 页面Schema Mapper接口
 * 对应表：ab_page_schema
 *
 * 重构说明：
 * - 统一使用幂等insert方法，Service层和ProjectionEngine共享
 * - 删除ProjectionMapper，所有ab_page_schema操作集中在此
 * - v2: page_type→kind, removed dsl_schema/page_category, added profile/layout/blocks/title(jsonb)
 */
@Mapper
public interface PageSchemaMapper extends BaseMapper<PageSchema> {

    // ==================== 幂等INSERT方法（统一使用） ====================

    /**
     * 插入页面Schema（幂等） - 用于Service层
     *
     * 使用 ON CONFLICT DO NOTHING 保证幂等性
     *
     * @param pageSchema 页面Schema实体
     * @return 实际插入的行数（0=已存在跳过, 1=新插入成功）
     */
    @Insert("""
        INSERT INTO ab_page_schema
        (pid, tenant_id, name, kind, profile,
         title, layout, blocks,
         version, semver, is_current, row_version, status, deleted_flag,
         release_id, release_pid, projected_at, created_at, updated_at)
        VALUES
        (#{pid}, #{tenantId}, #{name}, #{kind}, #{profile},
         #{title, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{layout, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{blocks, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{version}, #{semver}, #{isCurrent}, #{rowVersion}, #{status}, #{deletedFlag},
         #{releaseId}, #{releasePid}, #{projectedAt}, #{createdAt}, #{updatedAt})
        ON CONFLICT (tenant_id, name, version) DO NOTHING
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIdempotent(PageSchema pageSchema);

    /**
     * 插入页面Schema（幂等） - 用于ProjectionEngine
     *
     * 接受散列参数
     *
     * @return 实际插入的行数（0=已存在跳过, 1=新插入成功）
     */
    @Insert("""
        INSERT INTO ab_page_schema
        (pid, tenant_id, name, kind, profile,
         title, layout, blocks,
         version, semver, is_current, row_version, status, deleted_flag,
         release_id, release_pid, projected_at, created_at, updated_at)
        VALUES
        (#{pid}, #{tenantId}, #{name}, #{kind}, #{profile},
         #{title, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{layout, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{blocks, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{version}, #{semver}, true, 1, 'draft', false,
         #{releaseId}, #{releasePid}, NOW(), NOW(), NOW())
        ON CONFLICT (tenant_id, name, version) DO NOTHING
        """)
    int insertForProjection(
        @Param("pid") String pid,
        @Param("tenantId") Long tenantId,
        @Param("name") String name,
        @Param("kind") String kind,
        @Param("profile") String profile,
        @Param("title") String title,
        @Param("layout") String layout,
        @Param("blocks") String blocks,
        @Param("version") Integer version,
        @Param("semver") String semver,
        @Param("releaseId") Long releaseId,
        @Param("releasePid") String releasePid
    );

    // ==================== 投影辅助方法 ====================

    /**
     * 标记旧版本为非当前
     */
    @Update("UPDATE ab_page_schema SET is_current = false " +
            "WHERE tenant_id = #{tenantId} " +
            "AND name = #{name}")
    int markAsNotCurrent(
        @Param("tenantId") Long tenantId,
        @Param("name") String name
    );

    /**
     * 检查指定版本是否已存在
     */
    @Select("SELECT COUNT(*) FROM ab_page_schema " +
            "WHERE tenant_id = #{tenantId} " +
            "AND name = #{name} AND version = #{version}")
    int countByVersion(
        @Param("tenantId") Long tenantId,
        @Param("name") String name,
        @Param("version") Integer version
    );

    /**
     * 获取当前版本的PageSchema blocks数据（JSON格式）
     * 用于依赖分析和回滚
     */
    @Select("SELECT blocks::text FROM ab_page_schema " +
            "WHERE tenant_id = #{tenantId} " +
            "AND name = #{name} AND is_current = true")
    String getCurrentPageSchemaAsJson(
        @Param("tenantId") Long tenantId,
        @Param("name") String name
    );

    // ==================== 标准查询方法 ====================

    /**
     * 根据业务主键查询页面Schema
     * @param pid 业务主键
     * @return 页面Schema
     */
    @Select("SELECT * FROM ab_page_schema WHERE pid = #{pid} AND deleted_flag = false")
    PageSchema selectByPid(@Param("pid") String pid);

    /**
     * Load only the blocks JSON by PID.
     *
     * @param pid business key
     * @return blocks JSON string, or null if not found / deleted
     */
    @Select("SELECT blocks::text FROM ab_page_schema WHERE pid = #{pid} AND deleted_flag = FALSE")
    String selectBlocksByPid(@Param("pid") String pid);

    /**
     * 根据名称查询页面Schema
     * @param name 名称
     * @return 页面Schema
     */
    @Select("SELECT * FROM ab_page_schema WHERE name = #{name} AND deleted_flag = false")
    PageSchema selectByName(@Param("name") String name);

    /**
     * 根据页面kind查询页面Schema列表
     * @param kind 页面kind
     * @return 页面Schema列表
     */
    @Select("SELECT * FROM ab_page_schema WHERE kind = #{kind} AND deleted_flag = false ORDER BY sort_weight ASC")
    List<PageSchema> selectByKind(@Param("kind") String kind);

    /**
     * 查询已发布的页面Schema列表
     * @return 页面Schema列表
     */
    @Select("SELECT * FROM ab_page_schema WHERE status = 'published' AND deleted_flag = false ORDER BY published_at DESC")
    List<PageSchema> selectPublishedSchemas();

    /**
     * 查询模板页面Schema列表
     * @param templateCategory 模板分类（可选）
     * @return 页面Schema列表
     */
    @Select("""
        <script>
        SELECT * FROM ab_page_schema
        WHERE is_template = true
          AND deleted_flag = false
        <if test="templateCategory != null and templateCategory != ''">
          AND template_category = #{templateCategory}
        </if>
        ORDER BY sort_weight ASC
        </script>
        """)
    List<PageSchema> selectTemplateSchemas(@Param("templateCategory") String templateCategory);

    /**
     * 根据关键词搜索页面Schema
     * @param keyword 关键词
     * @return 页面Schema列表
     */
    @Select("""
        <script>
        SELECT * FROM ab_page_schema
        WHERE deleted_flag = false
        <if test="keyword != null and keyword != ''">
          AND (name LIKE CONCAT('%', #{keyword}, '%')
            OR title::text LIKE CONCAT('%', #{keyword}, '%')
            OR description LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        ORDER BY updated_at DESC
        </script>
        """)
    List<PageSchema> selectByKeyword(@Param("keyword") String keyword);

    /**
     * 分页查询页面Schema列表（不包含 blocks 字段以提升性能）
     * @param kind 页面kind（可选）
     * @param isTemplate 是否模板（可选）
     * @param isPublished 是否发布（可选）
     * @param keyword 关键词（可选）
     * @return 页面Schema列表
     */
    @Select("""
        <script>
        SELECT id, pid, tenant_id, page_key, model_code,
               name, title, description, kind, profile, meta_info,
               is_template, template_category, sort_weight,
               published_at, tags,
               version, semver, row_version, is_current,
               status, deleted_flag, created_at, updated_at
        FROM ab_page_schema
        WHERE deleted_flag = false
        <if test="kind != null and kind != ''">
          AND kind = #{kind}
        </if>
        <if test="isTemplate != null">
          AND is_template = #{isTemplate}
        </if>
        <if test="isPublished != null">
          AND status = CASE WHEN #{isPublished} = true THEN 'published' ELSE 'draft' END
        </if>
        <if test="keyword != null and keyword != ''">
          AND (name LIKE CONCAT('%', #{keyword}, '%')
            OR title::text LIKE CONCAT('%', #{keyword}, '%')
            OR description LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        ORDER BY updated_at DESC
        </script>
        """)
    IPage<PageSchema> selectPageList(
        Page<?> page,
        @Param("kind") String kind,
        @Param("isTemplate") Boolean isTemplate,
        @Param("isPublished") Boolean isPublished,
        @Param("keyword") String keyword
    );

    /**
     * 统计页面Schema数量（支持动态条件）
     * @param kind 页面kind（可选）
     * @param isTemplate 是否模板（可选）
     * @param isPublished 是否发布（可选）
     * @param keyword 关键词（可选）
     * @return 统计数量
     */
    @Select("""
        <script>
        SELECT COUNT(*) FROM ab_page_schema
        WHERE deleted_flag = false
        <if test="kind != null and kind != ''">
          AND kind = #{kind}
        </if>
        <if test="isTemplate != null">
          AND is_template = #{isTemplate}
        </if>
        <if test="isPublished != null">
          AND status = CASE WHEN #{isPublished} = true THEN 'published' ELSE 'draft' END
        </if>
        <if test="keyword != null and keyword != ''">
          AND (name LIKE CONCAT('%', #{keyword}, '%')
            OR title::text LIKE CONCAT('%', #{keyword}, '%')
            OR description LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        </script>
        """)
    long countByConditions(
        @Param("kind") String kind,
        @Param("isTemplate") Boolean isTemplate,
        @Param("isPublished") Boolean isPublished,
        @Param("keyword") String keyword
    );

    /**
     * 根据名称查询版本历史
     * @param name 页面名称
     * @return 版本历史列表
     */
    @Select("SELECT * FROM ab_page_schema WHERE name = #{name} AND deleted_flag = false ORDER BY version DESC")
    List<PageSchema> selectVersionsByName(@Param("name") String name);

    /**
     * 统计总数
     * @return 总数
     */
    @Select("SELECT COUNT(*) FROM ab_page_schema WHERE deleted_flag = false")
    long countTotal();

    /**
     * 统计已发布数量
     * @return 已发布数量
     */
    @Select("SELECT COUNT(*) FROM ab_page_schema WHERE status = 'published' AND deleted_flag = false")
    long countPublished();

    /**
     * 统计模板数量
     * @return 模板数量
     */
    @Select("SELECT COUNT(*) FROM ab_page_schema WHERE is_template = true AND deleted_flag = false")
    long countTemplates();

    /**
     * 检查名称唯一性
     * @param name 名称
     * @param excludePid 排除的PID（可选）
     * @return 统计数量
     */
    @Select("""
        <script>
        SELECT COUNT(*) FROM ab_page_schema
        WHERE name = #{name}
          AND deleted_flag = false
        <if test="excludePid != null and excludePid != ''">
          AND pid != #{excludePid}
        </if>
        </script>
        """)
    long countByName(@Param("name") String name, @Param("excludePid") String excludePid);

    /**
     * 根据页面唯一标识查询（推荐使用，仅返回已发布的页面）
     * @param pageKey 页面唯一标识，如 "device_list", "dashboard_main"
     * @return 页面Schema
     */
    @Select("SELECT * FROM ab_page_schema WHERE page_key = #{pageKey} AND status = 'published' AND deleted_flag = false")
    PageSchema selectByPageKey(@Param("pageKey") String pageKey);

    /**
     * 根据页面唯一标识查询（包含草稿和已发布，用于存在性检查）
     * @param pageKey 页面唯一标识
     * @return 页面Schema（无论是否发布）
     */
    @Select("SELECT * FROM ab_page_schema WHERE page_key = #{pageKey} AND deleted_flag = false LIMIT 1")
    PageSchema selectAnyByPageKey(@Param("pageKey") String pageKey);

    /**
     * 根据模型编码查询关联的所有页面
     * @param modelCode 模型编码
     * @return 页面Schema列表
     */
    @Select("SELECT * FROM ab_page_schema WHERE model_code = #{modelCode} AND status = 'published' AND deleted_flag = false ORDER BY kind")
    List<PageSchema> selectByModelCode(@Param("modelCode") String modelCode);

    /**
     * 检查 page_key 唯一性
     * @param pageKey 页面唯一标识
     * @param excludePid 排除的PID（可选）
     * @return 统计数量
     */
    @Select("""
        <script>
        SELECT COUNT(*) FROM ab_page_schema
        WHERE page_key = #{pageKey}
          AND deleted_flag = false
        <if test="excludePid != null and excludePid != ''">
          AND pid != #{excludePid}
        </if>
        </script>
        """)
    long countByPageKey(@Param("pageKey") String pageKey, @Param("excludePid") String excludePid);

    /**
     * 更新发布状态
     * @param pid 业务主键
     * @param status 状态 (PUBLISHED/DRAFT)
     * @param publishedAt 发布时间
     * @return 影响行数
     */
    @Update("UPDATE ab_page_schema SET status = #{status}, published_at = #{publishedAt} WHERE pid = #{pid}")
    int updatePublishStatus(@Param("pid") String pid,
                           @Param("status") String status,
                           @Param("publishedAt") Instant publishedAt);

    // ==================== Field Usage Support ====================

    /**
     * Count pages referencing a field code in their blocks
     * @param fieldCode Field code to search for
     * @return Number of pages referencing this field
     */
    @Select("SELECT COUNT(*) FROM ab_page_schema WHERE deleted_flag = false AND is_current = true AND blocks::text LIKE CONCAT('%', #{fieldCode}, '%')")
    int countByFieldCodeInDsl(@Param("fieldCode") String fieldCode);

    /**
     * Find page names referencing a field code in their blocks
     * @param fieldCode Field code to search for
     * @return List of page names
     */
    @Select("SELECT name FROM ab_page_schema WHERE deleted_flag = false AND is_current = true AND blocks::text LIKE CONCAT('%', #{fieldCode}, '%')")
    List<String> findPageNamesByFieldCodeInDsl(@Param("fieldCode") String fieldCode);

    // ==================== Plugin Import Support ====================

    /**
     * Update page schema fields for plugin import.
     */
    @Update("""
        UPDATE ab_page_schema SET
            name = #{name}, title = #{title}::jsonb, description = #{description}, kind = #{kind},
            profile = #{profile}, model_code = #{modelCode}, layout = #{layout}::jsonb,
            blocks = #{blocks}::jsonb, schema_version = #{schemaVersion},
            is_template = #{isTemplate}, template_category = #{templateCategory}, sort_weight = #{sortWeight},
            extension = #{extension}::jsonb,
            plugin_pid = #{pluginPid}, updated_at = NOW()
        WHERE pid = #{pid} AND tenant_id = #{tenantId}
        """)
    int updateForPluginImport(@Param("name") String name,
                              @Param("title") String title,
                              @Param("description") String description,
                              @Param("kind") String kind,
                              @Param("profile") String profile,
                              @Param("modelCode") String modelCode,
                              @Param("layout") String layout,
                              @Param("blocks") String blocks,
                              @Param("schemaVersion") int schemaVersion,
                              @Param("isTemplate") boolean isTemplate,
                              @Param("templateCategory") String templateCategory,
                              @Param("sortWeight") int sortWeight,
                              @Param("extension") String extension,
                              @Param("pluginPid") String pluginPid,
                              @Param("pid") String pid,
                              @Param("tenantId") Long tenantId);

    /**
     * Publish a page schema by pid (set status=PUBLISHED).
     */
    @Update("UPDATE ab_page_schema SET published_at = NOW(), status = 'published' WHERE pid = #{pid}")
    int publishByPid(@Param("pid") String pid);

    /**
     * Insert a new page schema for plugin import (bypasses service-layer name uniqueness validation).
     */
    @Insert("""
        INSERT INTO ab_page_schema (
            pid, tenant_id, namespace, env_id, is_current, status,
            extension, page_key, model_code,
            name, title, description, kind, profile,
            layout, blocks, schema_version,
            is_template, template_category, published_at,
            version, sort_weight, plugin_pid, created_at, updated_at
        ) VALUES (
            #{pid}, #{tenantId}, 'default', #{envId}, true, #{status},
            #{extension}::jsonb, #{pageKey}, #{modelCode},
            #{name}, #{title}::jsonb, #{description}, #{kind}, #{profile},
            #{layout}::jsonb, #{blocks}::jsonb, #{schemaVersion},
            #{isTemplate}, #{templateCategory}, #{publishedAt},
            '1', #{sortWeight}, #{pluginPid}, NOW(), NOW()
        )
        """)
    // env-layering PoC #16: native @Insert bypasses MetaObjectHandler, so env_id must be passed
    // explicitly by callers (resolved via EnvironmentService.findOrCreateDefaultId(tenantId)).
    int insertForPluginImport(@Param("pid") String pid,
                              @Param("tenantId") Long tenantId,
                              @Param("envId") Long envId,
                              @Param("status") String status,
                              @Param("pageKey") String pageKey,
                              @Param("modelCode") String modelCode,
                              @Param("name") String name,
                              @Param("title") String title,
                              @Param("description") String description,
                              @Param("kind") String kind,
                              @Param("profile") String profile,
                              @Param("layout") String layout,
                              @Param("blocks") String blocks,
                              @Param("schemaVersion") int schemaVersion,
                              @Param("isTemplate") boolean isTemplate,
                              @Param("templateCategory") String templateCategory,
                              @Param("publishedAt") Instant publishedAt,
                              @Param("sortWeight") int sortWeight,
                              @Param("extension") String extension,
                              @Param("pluginPid") String pluginPid);

    /**
     * Archive page schema by pid (fallback delete for plugin uninstall).
     */
    @Update("UPDATE ab_page_schema SET status = 'archived', deleted_flag = TRUE WHERE pid = #{pid}")
    int archiveByPid(@Param("pid") String pid);

    // ==================== Mobile Sync Support ====================

    /**
     * Get schema version metadata for schemas updated since a given timestamp.
     * Only returns published, non-deleted schemas.
     *
     * @param since timestamp threshold
     * @return lightweight version DTOs
     */
    @Select("SELECT page_key, schema_version, updated_at, kind, model_code " +
            "FROM ab_page_schema " +
            "WHERE updated_at > #{since} AND status = 'published' " +
            "AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
            "ORDER BY updated_at DESC")
    @Results({
        @Result(column = "page_key", property = "pageKey"),
        @Result(column = "schema_version", property = "schemaVersion"),
        @Result(column = "updated_at", property = "updatedAt"),
        @Result(column = "kind", property = "kind"),
        @Result(column = "model_code", property = "modelCode")
    })
    List<PageSchemaSyncVersionDTO> selectVersionsSince(@Param("since") Instant since);

    /**
     * Batch fetch full page schemas by page keys.
     * Only returns published, non-deleted schemas.
     *
     * @param keys list of page keys
     * @return full PageSchema entities
     */
    @Select("""
        <script>
        SELECT * FROM ab_page_schema
        WHERE page_key IN
        <foreach collection="keys" item="key" open="(" separator="," close=")">
            #{key}
        </foreach>
        AND status = 'published'
        AND (deleted_flag = FALSE OR deleted_flag IS NULL)
        </script>
        """)
    List<PageSchema> selectBatchByKeys(@Param("keys") List<String> keys);
}
