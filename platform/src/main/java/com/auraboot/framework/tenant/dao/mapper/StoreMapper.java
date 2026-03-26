package com.auraboot.framework.tenant.dao.mapper;

import com.auraboot.framework.tenant.dao.entity.Store;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 门店数据访问层接口
 * 提供门店相关的数据库操作
 */
@Mapper
public interface StoreMapper extends BaseMapper<Store> {

    /**
     * 根据PID查询门店
     *
     * @param pid 门店PID
     * @return 门店实体
     */
    @Select("SELECT * FROM ns_store WHERE pid = #{pid} AND deleted_flag = false")
    Store findByPid(@Param("pid") String pid);

    /**
     * 根据编码查询门店
     *
     * @param code 门店编码
     * @param tenantId 租户ID
     * @return 门店实体
     */
    @Select("SELECT * FROM ns_store WHERE code = #{code} AND  deleted_flag = false")
    Store findByCodeAndTenantId(@Param("code") String code, @Param("tenantId") Long tenantId);

    /**
     * 根据租户ID查询门店列表
     *
     * @param tenantId 租户ID
     * @return 门店列表
     */
    @Select("SELECT * FROM ns_store WHERE  deleted_flag = false ORDER BY created_at DESC")
    List<Store> findByTenantId(@Param("tenantId") Long tenantId);

    /**
     * 统计租户下的门店数量
     *
     * @param tenantId 租户ID
     * @return 门店数量
     */
    @Select("SELECT COUNT(*) FROM ns_store WHERE  deleted_flag = false")
    Long countByTenantId(@Param("tenantId") Long tenantId);

    /**
     * 分页查询门店列表（带条件）
     *
     * @param page 分页参数
     * @param tenantId 租户ID
     * @param name 门店名称（模糊查询）
     * @param code 门店编码（模糊查询）
     * @param type 门店类型
     * @param status 门店状态
     * @param keyword 关键词搜索
     * @return 分页结果
     */
    @Select("""
        <script>
        SELECT * FROM ns_store
        WHERE tenant_id = #{tenantId}
          AND deleted_flag = false
        <if test="name != null and name != ''">
          AND name LIKE CONCAT('%', #{name}, '%')
        </if>
        <if test="code != null and code != ''">
          AND code LIKE CONCAT('%', #{code}, '%')
        </if>
        <if test="type != null and type != ''">
          AND type = #{type}
        </if>
        <if test="status != null and status != ''">
          AND status = #{status}
        </if>
        <if test="keyword != null and keyword != ''">
          AND (name LIKE CONCAT('%', #{keyword}, '%')
               OR code LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        ORDER BY created_at DESC
        </script>
        """)
    Page<Store> selectPageWithConditions(
        Page<Store> page,
        @Param("tenantId") Long tenantId,
        @Param("name") String name,
        @Param("code") String code,
        @Param("type") String type,
        @Param("status") String status,
        @Param("keyword") String keyword
    );
}
