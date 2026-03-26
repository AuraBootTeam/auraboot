package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.Field;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 字段定义Mapper接口
 * 对应表：ab_meta_field
 * 
 * 重构说明：
 * - 统一使用幂等insert方法，Service层和ProjectionEngine共享
 * - 删除ProjectionMapper，所有ab_meta_field操作集中在此
 */
@Mapper
public interface MetaFieldMapper extends BaseMapper<Field> {

    // ==================== 幂等INSERT方法（统一使用） ====================
    
    /**
     * 插入字段（幂等） - 用于Service层
     * 
     * 使用 ON CONFLICT DO NOTHING 保证幂等性
     * 
     * @param field 字段实体
     * @return 实际插入的行数（0=已存在跳过, 1=新插入成功）
     */
    @Insert("""
        INSERT INTO ab_meta_field
        (pid, tenant_id,   code, data_type, ref_target, feature,
         extension, index_hint, ui_schema, query_schema, rule_schema, version, semver,
         is_current, row_version, status, deleted_flag, release_id, release_pid,
         projected_at, created_at, updated_at)
        VALUES
        (#{pid}, #{tenantId},   #{code}, #{dataType},
         #{refTarget, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler},
         #{feature, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler},
         #{extension, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler},
         #{indexHint, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler},
         #{uiSchema, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler},
         #{querySchema, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler},
         #{ruleSchema, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler},
         #{version}, #{semver}, #{isCurrent}, #{rowVersion}, #{status}, #{deletedFlag},
         #{releaseId}, #{releasePid}, #{projectedAt}, #{createdAt}, #{updatedAt})
        ON CONFLICT (tenant_id,   code, version) DO NOTHING
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIdempotent(Field field);
    

    // ==================== 投影辅助方法 ====================
    
    /**
     * 标记旧版本为非当前
     */
    @Update("UPDATE ab_meta_field SET is_current = false " +
            "WHERE tenant_id = #{tenantId}    " +
            "AND code = #{code}")
    int markAsNotCurrent(
        @Param("tenantId") Long tenantId,
             
             
        @Param("code") String code
    );
    
    /**
     * 检查指定版本是否已存在
     */
    @Select("SELECT COUNT(*) FROM ab_meta_field " +
            "WHERE tenant_id = #{tenantId}    " +
            "AND code = #{code} AND version = #{version}")
    int countByVersion(
        @Param("tenantId") Long tenantId,
             
             
        @Param("code") String code,
        @Param("version") Integer version
    );
    
    /**
     * 获取当前版本的Field数据（JSON格式）
     * 用于依赖分析和回滚
     */
    @Select("SELECT row_to_json(t) FROM (SELECT * FROM ab_meta_field " +
            "WHERE tenant_id = #{tenantId}    " +
            "AND code = #{code} AND is_current = true) t")
    String getCurrentFieldAsJson(
        @Param("tenantId") Long tenantId,
             
             
        @Param("code") String code
    );

    // ==================== 标准查询方法 ====================

    /**
     * 根据业务主键查询字段
     * @param pid 业务主键
     * @return 字段信息
     */
    @Select("SELECT * FROM ab_meta_field WHERE pid = #{pid} AND deleted_flag = false")
    Field findByPid(@Param("pid") String pid);

    /**
     * 根据PID和租户上下文查询字段
     * @param pid 业务主键
     * @param tenantId 租户ID
       
     * @return 字段信息
     */
    @Select("SELECT * FROM ab_meta_field WHERE pid = #{pid} AND tenant_id = #{tenantId}   AND deleted_flag = false")
    Field selectByPidWithContext(@Param("pid") String pid, @Param("tenantId") Long tenantId       );

    /**
     * 根据租户ID和字段键查询当前版本字段
     * @param tenantId 租户ID
       
     * @param code 字段键
     * @return 当前版本字段
     */
    @Select("SELECT * FROM ab_meta_field WHERE  code = #{code} AND is_current = TRUE AND deleted_flag = false")
    Field findCurrentByCode(@Param("code") String code);

    /**
     * 批量根据ID查询字段（优化N+1查询）
     * @param fieldIds 字段ID列表
     * @return 字段列表
     */
    @Select("""
        <script>
        SELECT * FROM ab_meta_field
        WHERE id IN
        <foreach collection="fieldIds" item="id" open="(" separator="," close=")">
            #{id}
        </foreach>
        AND deleted_flag = false
        </script>
        """)
    List<Field> findByIds(@Param("fieldIds") List<Long> fieldIds);

    /**
     * 根据租户ID和字段键查询指定版本字段
     * @param tenantId 租户ID
       
     * @param code 字段键
     * @param version 版本号
     * @return 指定版本字段
     */
    @Select("SELECT * FROM ab_meta_field WHERE  code = #{code} AND version = #{version} AND deleted_flag = false")
    Field findByCodeAndVersion(@Param("code") String code, @Param("version") Integer version);

    /**
     * 查询指定租户下的所有当前版本字段
     * @param tenantId 租户ID
       
     * @return 当前版本字段列表
     */
    @Select("SELECT * FROM ab_meta_field WHERE  is_current = TRUE AND deleted_flag = false ORDER BY created_at DESC")
    List<Field> findCurrentByTenant(       );

    /**
     * 查询指定字段的所有版本
     * @param tenantId 租户ID
       
     * @param code 字段键
     * @return 字段版本列表
     */
    @Select("SELECT * FROM ab_meta_field WHERE  code = #{code} AND deleted_flag = false ORDER BY version DESC")
    List<Field> findAllVersionsByCode(@Param("code") String code);

    /**
     * 分页查询字段列表（支持动态条件）
     * 注意：tenant_id 由 MetaContextMyBatisInterceptor 自动注入，不需要在 SQL 中声明
     * @param code 字段键（可选，模糊查询）
     * @param dataType 数据类型（可选）
     * @param status 状态（可选）
     * @param currentOnly 是否只查询当前版本
     * @return 字段列表
     */
    @Select("""
        <script>
        SELECT * FROM ab_meta_field
        WHERE deleted_flag = false
        <if test="code != null and code != ''">
          AND code LIKE CONCAT('%', #{code}, '%')
        </if>
        <if test="dataType != null and dataType != ''">
          AND data_type = #{dataType}
        </if>
        <if test="status != null and status != ''">
          AND status = #{status}
        </if>
        <if test="currentOnly != null and currentOnly == true">
          AND is_current = TRUE
        </if>
        ORDER BY created_at DESC
        </script>
        """)
    IPage<Field> selectPageList(
        Page<?> page,
        @Param("code") String code,
        @Param("dataType") String dataType,
        @Param("status") String status,
        @Param("currentOnly") Boolean currentOnly
    );

    /**
     * 统计字段数量（支持动态条件）
     * 注意：tenant_id 由 MetaContextMyBatisInterceptor 自动注入，不需要在 SQL 中声明
     * @param code 字段键（可选，模糊查询）
     * @param dataType 数据类型（可选）
     * @param status 状态（可选）
     * @param currentOnly 是否只查询当前版本
     * @return 统计数量
     */
    @Select("""
        <script>
        SELECT COUNT(*) FROM ab_meta_field
        WHERE deleted_flag = false
        <if test="code != null and code != ''">
          AND code LIKE CONCAT('%', #{code}, '%')
        </if>
        <if test="dataType != null and dataType != ''">
          AND data_type = #{dataType}
        </if>
        <if test="status != null and status != ''">
          AND status = #{status}
        </if>
        <if test="currentOnly != null and currentOnly == true">
          AND is_current = TRUE
        </if>
        </script>
        """)
    long countByConditions(
        @Param("code") String code,
        @Param("dataType") String dataType,
        @Param("status") String status,
        @Param("currentOnly") Boolean currentOnly
    );

    /**
     * 检查字段键是否存在
     * @param tenantId 租户ID
       
     * @param code 字段键
     * @param excludeId 排除的ID
     * @return 存在数量
     */
    @Select("""
        <script>
        SELECT COUNT(*)
        FROM ab_meta_field
        WHERE code = #{code}
          AND deleted_flag = false
        <if test="excludeId != null">
          AND id != #{excludeId}
        </if>
        </script>
        """)
    int countByCode(@Param("tenantId") Long tenantId,    @Param("code") String code, @Param("excludeId") Long excludeId);
    
    /**
     * 检查指定版本的字段是否存在
     * 用于投影引擎的幂等性检查
     * 
     * @param tenantId 租户ID
       
     * @param code 字段编码
     * @param version 版本号
     * @return true if exists, false otherwise
     */
    @Select("SELECT COUNT(*) > 0 FROM ab_meta_field " +
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
     * 获取指定字段的下一个版本号
     * @param tenantId 租户ID
       
     * @param code 字段键
     * @return 下一个版本号
     */
    @Select("SELECT COALESCE(MAX(version), 0) + 1 FROM ab_meta_field WHERE  code = #{code} AND deleted_flag = false")
    Integer getNextVersion(   @Param("code") String code);

    /**
     * 将指定字段的所有版本设置为非当前版本
     * @param tenantId 租户ID
       
     * @param code 字段键
     * @return 更新的记录数
     */
    @Update("UPDATE ab_meta_field SET is_current = FALSE WHERE  code = #{code} AND deleted_flag = false")
    int clearCurrentFlag(   @Param("code") String code);

    /**
     * 设置指定版本为当前版本
     * @param id 字段ID
     * @return 更新的记录数
     */
    @Update("UPDATE ab_meta_field SET is_current = TRUE WHERE id = #{id}")
    int setCurrentVersion(@Param("id") Long id);

    /**
     * 根据数据类型查询字段
     * @param tenantId 租户ID
       
     * @param dataType 数据类型
     * @return 字段列表
     */
    @Select("SELECT * FROM ab_meta_field WHERE  data_type = #{dataType} AND is_current = TRUE AND deleted_flag = false ORDER BY created_at DESC")
    List<Field> findByDataType(@Param("dataType") String dataType);

    /**
     * 根据数据源查询字段
     * @param dataSourceId 数据源ID
     * @return 字段列表
     */
    @Select("SELECT * FROM ab_meta_field WHERE data_source_id = #{dataSourceId} AND is_current = TRUE AND deleted_flag = false ORDER BY created_at DESC")
    List<Field> findByDataSource(@Param("dataSourceId") Long dataSourceId);

    /**
     * 根据状态查询字段
     * @param tenantId 租户ID
       
     * @param status 状态
     * @return 字段列表
     */
    @Select("SELECT * FROM ab_meta_field WHERE  status = #{status} AND is_current = TRUE AND deleted_flag = false ORDER BY created_at DESC")
    List<Field> findByStatus(@Param("status") String status);

    /**
     * 根据实体编码查询字段列表
     * 关联查询实体模型和字段，返回当前版本的字段
     *
     * @param entityCode 实体编码
     * @return 字段列表
     */
    @Select("""
        SELECT f.* FROM ab_meta_field f
        JOIN ab_meta_model m ON f.model_id = m.id
        WHERE m.code = #{entityCode}
          AND f.is_current = true
          AND m.is_current = true
          AND f.deleted_flag = false
          AND m.deleted_flag = false
        ORDER BY f.field_order, f.code
        """)
    List<Field> findByEntityCode(@Param("entityCode") String entityCode);

    /**
     * 物理删除测试数据 - 根据多个条件删除记录
     * @param code 字段键
     * @param tenantId 租户ID
       
     * @param version 版本
     * @return 删除的记录数
     */
    @Delete("DELETE FROM ab_meta_field WHERE code = #{code}    AND version = #{version}")
    int deleteByCodeAndTenantAndVersion(
        @Param("code") String code,
        @Param("tenantId") Long tenantId,
             
             
        @Param("version") Integer version
    );
    
    /**
     * 根据PIDs批量查询字段
     * @param pids 字段PID列表
     * @return 字段列表
     */
    @Select("""
        <script>
        SELECT * FROM ab_meta_field
        WHERE pid IN
        <foreach collection="pids" item="pid" open="(" separator="," close=")">
        #{pid}
        </foreach>
        AND is_current = TRUE
        </script>
        """)
    List<Field> findByPids(@Param("pids") List<String> pids);

    // ==================== Plugin Import Support ====================

    /**
     * Update field in place for plugin reimport (without creating new version).
     *
     * Used for OVERWRITE strategy during plugin reimport:
     * - Directly updates existing field record
     * - Does not trigger version creation
     * - Does not trigger uniqueness validation
     *
     * @param pid Field PID
     * @param dataType Data type
     * @param extension Extension properties (JSON)
     * @param pluginPid Plugin PID
     * @return Number of rows updated
     */
    @Update("""
        UPDATE ab_meta_field SET
            data_type = #{dataType},
            feature = #{feature, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler},
            ref_target = #{refTarget, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler},
            extension = #{extension, typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler},
            plugin_pid = #{pluginPid},
            updated_at = NOW()
        WHERE pid = #{pid}
        """)
    int updateFieldInPlace(
        @Param("pid") String pid,
        @Param("dataType") String dataType,
        @Param("feature") Object feature,
        @Param("refTarget") Object refTarget,
        @Param("extension") Object extension,
        @Param("pluginPid") String pluginPid
    );

    /**
     * Check if field exists by tenant and code (for plugin import).
     *
     * @param tenantId Tenant ID
     * @param code Field code
     * @return true if exists
     */
    @Select("SELECT COUNT(*) > 0 FROM ab_meta_field WHERE tenant_id = #{tenantId} AND code = #{code} AND is_current = TRUE AND deleted_flag = false")
    boolean existsByTenantAndCode(@Param("tenantId") Long tenantId, @Param("code") String code);

    /**
     * Archive field by pid (fallback delete for plugin uninstall).
     */
    @Update("UPDATE ab_meta_field SET status = 'archived', deleted_flag = TRUE WHERE pid = #{pid}")
    int archiveByPid(@Param("pid") String pid);
}
