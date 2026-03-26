package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.Dict;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 字典主表Mapper接口
 * 对应表：ab_dict
 * 
 * 重构说明：
 * - 统一使用幂等insert方法，Service层和ProjectionEngine共享
 * - 删除ProjectionMapper，所有ab_dict操作集中在此
 */
@Mapper
public interface DictMapper extends BaseMapper<Dict> {

    // ==================== 幂等INSERT方法（统一使用） ====================
    
    /**
     * 插入字典（幂等）
     * 
     * 使用 ON CONFLICT DO NOTHING 保证幂等性
     * 
     * @param dict 字典实体
     * @return 实际插入的行数（0=已存在跳过, 1=新插入成功）
     */
    @Insert("""
        INSERT INTO ab_dict
        (pid, tenant_id,   code, name, dict_type, version, semver,
         status, release_id, release_pid, is_current, created_at, updated_at)
        VALUES
        (#{pid}, #{tenantId},   #{code}, #{name}, #{dictType}, 
         #{version}, #{semver}, #{status}, #{releaseId}, #{releasePid}, #{isCurrent}, 
         #{createdAt}, #{updatedAt})
        ON CONFLICT (tenant_id,   code, version) DO NOTHING
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIdempotent(Dict dict);
    
    // ==================== 投影辅助方法 ====================
    
    /**
     * 标记旧版本为非当前
     */
    @Update("UPDATE ab_dict SET is_current = false " +
            "WHERE tenant_id = #{tenantId}    " +
            "AND code = #{code}")
    int markAsNotCurrent(
        @Param("tenantId") Long tenantId,
             
             
        @Param("code") String code
    );
    
    /**
     * 根据PID获取字典ID
     */
    @Select("SELECT id FROM ab_dict WHERE pid = #{pid}")
    Long getIdByPid(@Param("pid") String pid);
    
    /**
     * 检查指定版本是否已存在
     */
    @Select("SELECT COUNT(*) FROM ab_dict " +
            "WHERE tenant_id = #{tenantId}    " +
            "AND code = #{code} AND version = #{version}")
    int countByVersion(
        @Param("tenantId") Long tenantId,
             
             
        @Param("code") String code,
        @Param("version") Integer version
    );
    
    /**
     * 获取当前版本的Dict数据（JSON格式）
     * 用于依赖分析和回滚
     */
    @Select("SELECT row_to_json(t) FROM (SELECT * FROM ab_dict " +
            "WHERE tenant_id = #{tenantId}    " +
            "AND code = #{code} AND is_current = true) t")
    String getCurrentDictAsJson(
        @Param("tenantId") Long tenantId,
             
             
        @Param("code") String code
    );

    // ==================== 标准查询方法 ====================

    /**
     * 根据业务主键查询字典
     * @param pid 业务主键
     * @return 字典信息
     */
    @Select("SELECT * FROM ab_dict WHERE pid = #{pid}")
    Dict findByPid(@Param("pid") String pid);

    /**
     * 根据租户ID和字典编码查询当前版本字典
     * @param tenantId 租户ID
       
     * @param code 字典编码
     * @return 当前版本字典
     */
    @Select("SELECT * FROM ab_dict  WHERE    code = #{code} AND is_current = TRUE")
    Dict findCurrentByCode(  @Param("code") String code);

    /**
     * 根据租户ID和字典编码查询指定版本字典
     * @param tenantId 租户ID
       
     * @param code 字典编码
     * @param version 版本号
     * @return 指定版本字典
     */
    @Select("SELECT * FROM ab_dict  WHERE    code = #{code} AND version = #{version}")
    Dict findByCodeAndVersion(  @Param("code") String code, @Param("version") Integer version);

    /**
     * 查询指定租户下的所有当前版本字典
     * @param tenantId 租户ID
       
     * @return 当前版本字典列表
     */
    @Select("SELECT * FROM ab_dict  WHERE    is_current = TRUE ORDER BY created_at DESC")
    List<Dict> findCurrentByTenant(     );

    /**
     * 查询指定字典的所有版本
     * @param tenantId 租户ID
       
     * @param code 字典编码
     * @return 字典版本列表
     */
    @Select("SELECT * FROM ab_dict  WHERE    code = #{code} ORDER BY version DESC")
    List<Dict> findAllVersionsByCode(  @Param("code") String code);

    /**
     * 根据字典类型查询字典
     * @param tenantId 租户ID
       
     * @param dictType 字典类型
     * @return 字典列表
     */
    @Select("SELECT * FROM ab_dict  WHERE    dict_type = #{dictType} AND is_current = TRUE ORDER BY created_at DESC")
    List<Dict> findByDictType(  @Param("dictType") String dictType);

    /**
     * 根据状态查询字典
     * @param tenantId 租户ID
       
     * @param status 状态
     * @return 字典列表
     */
    @Select("SELECT * FROM ab_dict  WHERE    status = #{status} AND is_current = TRUE ORDER BY created_at DESC")
    List<Dict> findByStatus(  @Param("status") String status);

    /**
     * 检查字典编码是否存在
     * @param tenantId 租户ID
       
     * @param code 字典编码
     * @param excludeId 排除的ID
     * @return 存在数量
     */
    @Select("""
        <script>
        SELECT COUNT(*)
        FROM ab_dict
        WHERE code = #{code}
        <if test="excludeId != null">
          AND id != #{excludeId}
        </if>
        </script>
        """)
    int countByCode(  @Param("code") String code, @Param("excludeId") Long excludeId);
    
    /**
     * 检查指定版本的字典是否存在
     * 用于投影引擎的幂等性检查
     * 
     * @param tenantId 租户ID
       
     * @param code 字典编码
     * @param version 版本号
     * @return true if exists, false otherwise
     */
    @Select("SELECT COUNT(*) > 0 FROM ab_dict " +
            "WHERE tenant_id = #{tenantId} " +
            "     " +
            "  " +
            "  AND code = #{code} " +
            "  AND version = #{version}")
    boolean existsByCodeAndVersion(
        @Param("tenantId") Long tenantId,
             
             
        @Param("code") String code,
        @Param("version") Integer version
    );



    /**
     * 获取指定字典的下一个版本号
     * @param tenantId 租户ID
       
     * @param code 字典编码
     * @return 下一个版本号
     */
    @Select("SELECT COALESCE(MAX(version), 0) + 1 FROM ab_dict  WHERE    code = #{code}")
    Integer getNextVersion(  @Param("code") String code);

    /**
     * 将指定字典的所有版本设置为非当前版本
     * @param tenantId 租户ID
       
     * @param code 字典编码
     * @return 更新的记录数
     */
    @Update("UPDATE ab_dict SET is_current = FALSE  WHERE    code = #{code}")
    int clearCurrentFlag(  @Param("code") String code);

    /**
     * 设置指定版本为当前版本
     * @param id 字典ID
     * @return 更新的记录数
     */
    @Update("UPDATE ab_dict SET is_current = TRUE WHERE id = #{id}")
    int setCurrentVersion(@Param("id") Long id);

    /**
     * 查询静态字典（小字典）
     * @param tenantId 租户ID
       
     * @return 静态字典列表
     */
    @Select("SELECT * FROM ab_dict  WHERE    dict_type = 'static' AND is_current = TRUE AND status = 'enabled' ORDER BY created_at DESC")
    List<Dict> findStaticDicts(     );

    /**
     * 查询动态字典（大字典）
     * @param tenantId 租户ID
       
     * @return 动态字典列表
     */
    @Select("SELECT * FROM ab_dict  WHERE    dict_type = 'dynamic' AND is_current = TRUE AND status = 'enabled' ORDER BY created_at DESC")
    List<Dict> findDynamicDicts(     );

    /**
     * 查询级联字典
     * @param tenantId 租户ID
       
     * @return 级联字典列表
     */
    @Select("SELECT * FROM ab_dict  WHERE    dict_type = 'cascade' AND is_current = TRUE AND status = 'enabled' ORDER BY created_at DESC")
    List<Dict> findCascadeDicts(     );

    /**
     * 根据名称模糊查询字典
     * @param tenantId 租户ID
       
     * @param namePattern 名称模式
     * @return 字典列表
     */
    @Select("SELECT * FROM ab_dict  WHERE    name LIKE #{namePattern} AND is_current = TRUE ORDER BY created_at DESC")
    List<Dict> findByNamePattern(  @Param("namePattern") String namePattern);

    /**
     * 根据租户和状态查询字典列表
     * 
     * @param tenantId 租户ID
       
     * @param status 状态（可选）
     * @return 字典列表
     */
    @Select("""
        <script>
        SELECT * FROM ab_dict
        WHERE tenant_id = #{tenantId}
          AND is_current = TRUE
          AND status != 'disabled'
        <if test="status != null and status != ''">
          AND status = #{status}
        </if>
        ORDER BY updated_at DESC
        </script>
        """)
    List<Dict> selectByTenantAndStatus(
        @Param("tenantId") Long tenantId,
             
             
        @Param("status") String status
    );

    /**
     * 根据类型查询字典列表
     * 
     * @param tenantId 租户ID
       
     * @param dictType 字典类型
     * @return 字典列表
     */
    @Select("SELECT * FROM ab_dict " +
            "WHERE tenant_id = #{tenantId} " +
            "     " +
            "  " +
            "  AND dict_type = #{dictType} " +
            "  AND status != 'disabled' " +
            "ORDER BY updated_at DESC")
    List<Dict> selectByType(
        @Param("tenantId") Long tenantId,
             
             
        @Param("dictType") String dictType
    );

    /**
     * 关键词搜索字典
     * 
     * @param tenantId 租户ID
       
     * @param keyword 关键词
     * @return 字典列表
     */
    @Select("""
        <script>
        SELECT * FROM ab_dict
        WHERE tenant_id = #{tenantId}
          AND is_current = TRUE
          AND status != 'disabled'
        <if test="keyword != null and keyword != ''">
          AND (code LIKE CONCAT('%', #{keyword}, '%')
            OR name LIKE CONCAT('%', #{keyword}, '%')
            OR description LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        ORDER BY updated_at DESC
        </script>
        """)
    List<Dict> searchByKeyword(
        @Param("tenantId") Long tenantId,
             
             
        @Param("keyword") String keyword
    );

    /**
     * 分页查询字典列表（支持动态条件）
     * 
     * @param tenantId 租户ID
       
     * @param code 字典编码（可选）
     * @param name 字典名称（可选）
     * @param dictType 字典类型（可选）
     * @param status 状态（可选）
     * @return 字典列表
     */
    @Select("""
        <script>
        SELECT * FROM ab_dict
        WHERE tenant_id = #{tenantId}
          AND is_current = TRUE
          AND status != 'disabled'
        <if test="code != null and code != ''">
          AND code LIKE CONCAT('%', #{code}, '%')
        </if>
        <if test="name != null and name != ''">
          AND name LIKE CONCAT('%', #{name}, '%')
        </if>
        <if test="dictTypes != null and dictTypes.size() > 0">
          AND dict_type IN
          <foreach item="dt" collection="dictTypes" open="(" separator="," close=")">
            #{dt}
          </foreach>
        </if>
        <if test="status != null and status != ''">
          AND status = #{status}
        </if>
        ORDER BY created_at DESC
        </script>
        """)
    IPage<Dict> selectPageList(
        Page<?> page,
        @Param("tenantId") Long tenantId,
        @Param("code") String code,
        @Param("name") String name,
        @Param("dictTypes") List<String> dictTypes,
        @Param("status") String status
    );

    /**
     * 统计字典数量（支持动态条件）
     * 
     * @param tenantId 租户ID
       
     * @param code 字典编码（可选）
     * @param name 字典名称（可选）
     * @param dictType 字典类型（可选）
     * @param status 状态（可选）
     * @return 字典数量
     */
    @Select("""
        <script>
        SELECT COUNT(*) FROM ab_dict
        WHERE status != 'disabled'
        <if test="code != null and code != ''">
          AND code LIKE CONCAT('%', #{code}, '%')
        </if>
        <if test="name != null and name != ''">
          AND name LIKE CONCAT('%', #{name}, '%')
        </if>
        <if test="dictType != null and dictType != ''">
          AND dict_type = #{dictType}
        </if>
        <if test="status != null and status != ''">
          AND status = #{status}
        </if>
        </script>
        """)
    long countByConditions(
        @Param("tenantId") Long tenantId,
             
             
        @Param("code") String code,
        @Param("name") String name,
        @Param("dictType") String dictType,
        @Param("status") String status
    );

    /**
     * 根据编码列表查询字典
     * 
     * @param tenantId 租户ID
       
     * @param codes 编码列表
     * @return 字典列表
     */
    @Select("""
        <script>
        SELECT * FROM ab_dict
        WHERE code IN
        <foreach collection="codes" item="code" open="(" separator="," close=")">
          #{code}
        </foreach>
          AND status != 'disabled'
        ORDER BY code ASC
        </script>
        """)
    List<Dict> selectByCodes(
        @Param("tenantId") Long tenantId,
             
             
        @Param("codes") List<String> codes
    );

    /**
     * 根据编码和版本号查询字典
     * 
     * @param tenantId 租户ID
       
     * @param code 字典编码
     * @param version 版本号
     * @return 字典
     */
    @Select("SELECT * FROM ab_dict " +
            "WHERE tenant_id = #{tenantId} " +
            "     " +
            "  " +
            "  AND code = #{code} " +
            "  AND version = #{version}")
    Dict selectByCodeAndVersion(
        @Param("tenantId") Long tenantId,
             
             
        @Param("code") String code,
        @Param("version") Integer version
    );

    /**
     * 根据PID查询字典（带租户上下文）
     * 
     * @param pid 业务主键
     * @param tenantId 租户ID
       
     * @return 字典
     */
    @Select("SELECT * FROM ab_dict " +
            "WHERE pid = #{pid} " +
            "  AND tenant_id = #{tenantId} " +
            "     " +
            "  ")
    Dict selectByPidWithContext(
        @Param("pid") String pid,
        @Param("tenantId") Long tenantId
    );

    /**
     * 查询字典的所有版本
     * 
     * @param tenantId 租户ID
       
     * @param code 字典编码
     * @return 版本列表
     */
    @Select("SELECT * FROM ab_dict " +
            "WHERE tenant_id = #{tenantId} " +
            "     " +
            "  " +
            "  AND code = #{code} " +
            "  AND status != 'disabled' " +
            "ORDER BY version DESC")
    List<Dict> selectVersionsByCode(
        @Param("tenantId") Long tenantId,
             
             
        @Param("code") String code
    );

    /**
     * 物理删除测试数据 - 根据多个条件删除记录
     * @param code 代码
     * @param tenantId 租户ID
       
     * @param version 版本
     * @return 删除的记录数
     */
    @Delete("DELETE FROM ab_dict WHERE code = #{code}    AND version = #{version}")
    int deleteByCodeAndTenantAndVersion(
        @Param("code") String code,
        @Param("tenantId") Long tenantId,
             
             
        @Param("version") Integer version
    );

    /**
     * 物理删除测试数据 - 根据code删除所有版本
     * 用于测试清理
     * 
     * @param code 字典编码
     * @param tenantId 租户ID
       
     * @return 删除的记录数
     */
    @Delete("DELETE FROM ab_dict WHERE code = #{code} AND tenant_id = #{tenantId}  ")
    int deleteByCode(
        @Param("code") String code,
        @Param("tenantId") Long tenantId


    );

    // ==================== Plugin Import Support ====================

    /**
     * Update plugin_pid for a dict by pid.
     */
    @Update("UPDATE ab_dict SET plugin_pid = #{pluginPid} WHERE pid = #{pid}")
    int updatePluginPidByPid(@Param("pluginPid") String pluginPid, @Param("pid") String pid);

    /**
     * Soft delete dict by pid with status change (fallback delete for plugin uninstall).
     */
    @Update("UPDATE ab_dict SET status = 'disabled', deleted_flag = TRUE WHERE pid = #{pid}")
    int softDeleteByPid(@Param("pid") String pid);
}
