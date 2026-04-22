package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.Model;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 业务实体模型Mapper接口
 * 对应表：ab_meta_model
 * 
 * 重构说明：
 * - 统一使用幂等insert方法，Service层和ProjectionEngine共享
 * - 删除ProjectionMapper，所有ab_meta_model操作集中在此
 */
@Mapper
public interface MetaModelMapper extends BaseMapper<Model> {

    // ==================== 幂等INSERT方法（统一使用） ====================
    
    /**
     * 插入模型（幂等）
     * 
     * 使用 ON CONFLICT DO NOTHING 保证幂等性
     * 
     * 适用场景：
     * - Service层直接创建
     * - ProjectionEngine投影
     * 
     * @param model 模型实体
     * @return 实际插入的行数（0=已存在跳过, 1=新插入成功）
     */
    @Insert("""
        INSERT INTO ab_meta_model
        (pid, tenant_id,   code, extension, version,
         semver, is_current, row_version, status, deleted_flag, 
         release_id, release_pid, projected_at, created_at, updated_at)
        VALUES
        (#{pid}, #{tenantId},   #{code}, 
         #{extension, typeHandler=com.auraboot.framework.application.database.mybatis.ExtensionTypeHandler},
         #{version}, #{semver}, #{isCurrent}, #{rowVersion}, #{status}, #{deletedFlag},
         #{releaseId}, #{releasePid}, #{projectedAt}, #{createdAt}, #{updatedAt})
        ON CONFLICT (tenant_id,   code, version) DO NOTHING
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIdempotent(Model model);
    
    /**
     * 批量插入模型（幂等）
     * 
     * 性能优化：批量插入比单条插入快10-15倍
     * 建议批量大小：每批不超过500条
     * 
     * @param models 模型列表
     * @return 实际插入的行数
     */
    @Insert("""
        <script>
        INSERT INTO ab_meta_model
        (pid, tenant_id,   code, extension, version,
         semver, is_current, row_version, status, deleted_flag,
         release_id, release_pid, projected_at, created_at, updated_at)
        VALUES
        <foreach collection="models" item="m" separator=",">
        (#{m.pid}, #{m.tenantId}, #{m.code},
         #{m.extension, typeHandler=com.auraboot.framework.application.database.mybatis.ExtensionTypeHandler},
         #{m.version}, #{m.semver}, #{m.isCurrent}, #{m.rowVersion}, #{m.status}, #{m.deletedFlag},
         #{m.releaseId}, #{m.releasePid}, #{m.projectedAt}, #{m.createdAt}, #{m.updatedAt})
        </foreach>
        ON CONFLICT (tenant_id,   code, version) DO NOTHING
        </script>
        """)
    int batchInsertIdempotent(@Param("models") List<Model> models);
    
    // ==================== 投影辅助方法 ====================
    
    /**
     * 标记旧版本为非当前
     * 用于投影新版本前，将同一code的旧版本标记为非当前
     */
    @Update("UPDATE ab_meta_model SET is_current = false " +
            "WHERE tenant_id = #{tenantId}    " +
            "AND code = #{code}")
    int markAsNotCurrent(
        @Param("tenantId") Long tenantId,
             
             
        @Param("code") String code
    );
    
    /**
     * 检查指定版本是否已存在
     * 用于投影前的幂等性检查
     */
    @Select("SELECT COUNT(*) FROM ab_meta_model " +
            "WHERE tenant_id = #{tenantId}    " +
            "AND code = #{code} AND version = #{version}")
    int countByVersion(
        @Param("tenantId") Long tenantId,
             
             
        @Param("code") String code,
        @Param("version") Integer version
    );
    
    /**
     * 获取当前版本的Model数据（JSON格式）
     * 用于依赖分析和回滚
     */
    @Select("SELECT row_to_json(t) FROM (SELECT * FROM ab_meta_model " +
            "WHERE tenant_id = #{tenantId}    " +
            "AND code = #{code} AND is_current = true) t")
    String getCurrentModelAsJson(
        @Param("tenantId") Long tenantId,
             
             
        @Param("code") String code
    );

    // ==================== 标准查询方法 ====================

    /**
     * 根据业务主键查询模型
     * @param pid 业务主键
     * @return 模型信息
     */
    @Select("SELECT * FROM ab_meta_model WHERE pid = #{pid} AND deleted_flag = false")
    Model findByPid(@Param("pid") String pid);

    /**
     * 根据业务主键查询模型（包含逻辑删除）
     * @param pid 业务主键
     * @return 模型信息
     */
    @Select("SELECT * FROM ab_meta_model WHERE pid = #{pid}")
    Model findByPidIncludingDeleted(@Param("pid") String pid);

    /**
     * 根据租户ID和模型编码查询当前版本模型
     * @param code 模型编码
     * @return 当前版本模型
     */
    @Select("SELECT * FROM ab_meta_model WHERE code = #{code} AND is_current = TRUE AND deleted_flag = false")
    Model findCurrentByCode(@Param("code") String code);

    /**
     * 根据租户ID和模型编码查询指定版本模型
     * @param tenantId 租户ID
       
     * @param code 模型编码
     * @param version 版本号
     * @return 指定版本模型
     */
    @Select("SELECT * FROM ab_meta_model  WHERE    code = #{code} AND version = #{version} AND deleted_flag = false")
    Model findByCodeAndVersion(@Param("code") String code, @Param("version") Integer version);

    /**
     * 查询指定租户下的所有当前版本模型
     * @param tenantId 租户ID
       
     * @return 当前版本模型列表
     */
    @Select("SELECT * FROM ab_meta_model  WHERE    is_current = TRUE AND deleted_flag = false ORDER BY created_at DESC")
    List<Model> findCurrentByTenant(     );

    /**
     * 查询指定模型的所有版本

     * @param code 模型编码
     * @return 模型版本列表
     */
    @Select("SELECT * FROM ab_meta_model  WHERE    code = #{code} AND deleted_flag = false ORDER BY version DESC")
    List<Model> findAllVersionsByCode(@Param("code") String code);

    /**
     * 检查模型编码是否存在

     * @param code 模型编码
     * @param excludeId 排除的ID
     * @return 存在数量
     */
    @Select("""
        <script>
        SELECT COUNT(*)
        FROM ab_meta_model
        WHERE code = #{code}
          AND deleted_flag = false
        <if test="excludeId != null">
          AND id != #{excludeId}
        </if>
        </script>
        """)
    int countByCode( @Param("code") String code, @Param("excludeId") Long excludeId);
    
    /**
     * 检查指定版本的模型是否存在
     * 用于投影引擎的幂等性检查
     * 
     * @param tenantId 租户ID
       
     * @param code 模型编码
     * @param version 版本号
     * @return true if exists, false otherwise
     */
    @Select("SELECT COUNT(*) > 0 FROM ab_meta_model " +
            "WHERE tenant_id = #{tenantId} " +
            "     " +
            "  " +
            "  AND code = #{code} " +
            "  AND version = #{version} " +
            "  AND deleted_flag = false")
    boolean existsByCodeAndVersion(
        @Param("tenantId") Long tenantId,
             
             
        @Param("code") String code,
        @Param("version") Integer version
    );

    /**
     * 获取指定模型的下一个版本号
     * @param code 模型编码
     * @return 下一个版本号
     */
    @Select("SELECT COALESCE(MAX(version), 0) + 1 FROM ab_meta_model  WHERE    code = #{code} AND deleted_flag = false")
    Integer getNextVersion( @Param("code") String code);

    /**
     * 将指定模型的所有版本设置为非当前版本
     * @param tenantId 租户ID
       
     * @param code 模型编码
     * @return 更新的记录数
     */
    @Update("UPDATE ab_meta_model SET is_current = FALSE WHERE tenant_id = #{tenantId}   AND code = #{code} AND deleted_flag = false")
    int markOldVersionsAsNonCurrent(@Param("tenantId") Long tenantId,    @Param("code") String code);

    /**
     * 将指定模型的所有版本设置为非当前版本
     * @param code 模型编码
     * @return 更新的记录数
     */
    @Update("UPDATE ab_meta_model SET is_current = FALSE  WHERE    code = #{code} AND deleted_flag = false")
    int clearCurrentFlag( @Param("code") String code);

    /**
     * 设置指定版本为当前版本
     * @param id 模型ID
     * @return 更新的记录数
     */
    @Update("UPDATE ab_meta_model SET is_current = TRUE WHERE id = #{id}")
    int setCurrentVersion(@Param("id") Long id);

    /**
     * 根据状态查询模型
     * @param status 状态
     * @return 模型列表
     */
    @Select("SELECT * FROM ab_meta_model  WHERE    status = #{status} AND is_current = TRUE AND deleted_flag = false ORDER BY created_at DESC")
    List<Model> findByStatus(@Param("status") String status);

    /**
     * 物理删除测试数据 - 根据多个条件删除记录
     * @param code 代码
     * @param tenantId 租户ID
       
     * @param version 版本
     * @return 删除的记录数
     */
    @Delete("DELETE FROM ab_meta_model WHERE code = #{code}    AND version = #{version}")
    int deleteByCodeAndTenantAndVersion(
        @Param("code") String code,
        @Param("tenantId") Long tenantId,
             
             
        @Param("version") Integer version
    );

    // ==================== Plugin Import Methods ====================

    /**
     * Update model fields for plugin import (extension, plugin_pid, table_name, model_category).
     * table_name is only overwritten when the plugin explicitly provides one; a null value
     * preserves the existing column so that dynamic-table models (mt_*) are not cleared.
     */
    @Update("""
        UPDATE ab_meta_model SET
            extension = #{extension}::jsonb,
            plugin_pid = #{pluginPid},
            table_name = COALESCE(#{tableName}, table_name),
            model_category = #{modelCategory},
            updated_at = NOW()
        WHERE tenant_id = #{tenantId} AND code = #{code} AND deleted_flag = FALSE
        """)
    int updateForPluginImport(@Param("extension") String extension,
                              @Param("pluginPid") String pluginPid,
                              @Param("tableName") String tableName,
                              @Param("modelCategory") String modelCategory,
                              @Param("tenantId") Long tenantId,
                              @Param("code") String code);

    /**
     * Update table_name by pid (used after soft-delete resurrection).
     */
    @Update("UPDATE ab_meta_model SET table_name = #{tableName} WHERE pid = #{pid}")
    int updateTableNameByPid(@Param("tableName") String tableName, @Param("pid") String pid);

    /**
     * Archive model by pid (fallback delete for plugin uninstall).
     */
    @Update("UPDATE ab_meta_model SET status = 'archived', deleted_flag = TRUE WHERE pid = #{pid}")
    int archiveByPid(@Param("pid") String pid);

    /**
     * Search models by keyword (searches in code, displayName, and description)
     * @param keyword Search keyword
     * @param modelType Model type filter
     * @param status Status filter
     * @param sourceType Source type filter
     * @param currentOnly Whether to only return current versions
     * @param offset Pagination offset
     * @param limit Pagination limit
     * @return List of matching models
     */
    @Select("""
        <script>
        SELECT * FROM ab_meta_model
        WHERE deleted_flag = false
        <if test="currentOnly != null and currentOnly">
          AND is_current = TRUE
        </if>
        <if test="keyword != null and keyword != ''">
          AND (code LIKE CONCAT('%', #{keyword}, '%')
               OR COALESCE(extension->>'displayName', extension->'extension'->>'displayName') LIKE CONCAT('%', #{keyword}, '%')
               OR COALESCE(extension->>'description', extension->'extension'->>'description') LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        <if test="modelType != null and modelType != ''">
          AND COALESCE(extension->>'modelType', extension->'extension'->>'modelType') = #{modelType}
        </if>
        <if test="status != null and status != ''">
          AND status = #{status}
        </if>
        <if test="sourceType != null and sourceType != ''">
          <choose>
            <when test="sourceType == 'physical'">
              AND (source_type = 'physical' OR source_type IS NULL OR source_type = '')
            </when>
            <otherwise>
              AND source_type = #{sourceType}
            </otherwise>
          </choose>
        </if>
        ORDER BY
        <choose>
          <when test="sortField == 'code'">code</when>
          <when test="sortField == 'displayName'">COALESCE(extension->>'displayName', extension->'extension'->>'displayName')</when>
          <when test="sortField == 'status'">status</when>
          <when test="sortField == 'version'">version</when>
          <when test="sortField == 'createdAt'">created_at</when>
          <otherwise>created_at</otherwise>
        </choose>
        <choose>
          <when test="sortOrder == 'asc'">ASC</when>
          <otherwise>DESC</otherwise>
        </choose>
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Model> searchByKeyword(
        @Param("keyword") String keyword,
        @Param("modelType") String modelType,
        @Param("status") String status,
        @Param("sourceType") String sourceType,
        @Param("sortField") String sortField,
        @Param("sortOrder") String sortOrder,
        @Param("currentOnly") Boolean currentOnly,
        @Param("offset") long offset,
        @Param("limit") long limit
    );

    /**
     * Count models matching search criteria
     * @param keyword Search keyword
     * @param modelType Model type filter
     * @param status Status filter
     * @param sourceType Source type filter
     * @param currentOnly Whether to only count current versions
     * @return Total count
     */
    @Select("""
        <script>
        SELECT COUNT(*) FROM ab_meta_model
        WHERE deleted_flag = false
        <if test="currentOnly != null and currentOnly">
          AND is_current = TRUE
        </if>
        <if test="keyword != null and keyword != ''">
          AND (code LIKE CONCAT('%', #{keyword}, '%')
               OR COALESCE(extension->>'displayName', extension->'extension'->>'displayName') LIKE CONCAT('%', #{keyword}, '%')
               OR COALESCE(extension->>'description', extension->'extension'->>'description') LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        <if test="modelType != null and modelType != ''">
          AND COALESCE(extension->>'modelType', extension->'extension'->>'modelType') = #{modelType}
        </if>
        <if test="status != null and status != ''">
          AND status = #{status}
        </if>
        <if test="sourceType != null and sourceType != ''">
          <choose>
            <when test="sourceType == 'physical'">
              AND (source_type = 'physical' OR source_type IS NULL OR source_type = '')
            </when>
            <otherwise>
              AND source_type = #{sourceType}
            </otherwise>
          </choose>
        </if>
        </script>
        """)
    long countByKeyword(
        @Param("keyword") String keyword,
        @Param("modelType") String modelType,
        @Param("status") String status,
        @Param("sourceType") String sourceType,
        @Param("currentOnly") Boolean currentOnly
    );
}
