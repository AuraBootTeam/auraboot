package com.auraboot.framework.connector.mapper;

import com.auraboot.framework.connector.entity.ApiConnector;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for ApiConnector entity.
 *
 * @since 5.1.0
 */
@Mapper
public interface ApiConnectorMapper extends BaseMapper<ApiConnector> {

    @Select("SELECT * FROM ab_api_connector WHERE tenant_id = #{tenantId} AND pid = #{pid}")
    ApiConnector findByPid(@Param("tenantId") Long tenantId, @Param("pid") String pid);

    @Select("SELECT * FROM ab_api_connector WHERE tenant_id = #{tenantId} ORDER BY created_at DESC")
    List<ApiConnector> findByTenant(@Param("tenantId") Long tenantId);

    @Delete("DELETE FROM ab_api_connector WHERE tenant_id = #{tenantId} AND pid = #{pid}")
    int deleteByPid(@Param("tenantId") Long tenantId, @Param("pid") String pid);
}
