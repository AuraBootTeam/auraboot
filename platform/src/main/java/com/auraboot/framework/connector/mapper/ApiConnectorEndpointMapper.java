package com.auraboot.framework.connector.mapper;

import com.auraboot.framework.connector.entity.ApiConnectorEndpoint;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for ApiConnectorEndpoint entity.
 *
 * @since 5.1.0
 */
@Mapper
public interface ApiConnectorEndpointMapper extends BaseMapper<ApiConnectorEndpoint> {

    @Select("""
        SELECT * FROM ab_api_connector_endpoint
        WHERE connector_pid = #{connectorPid} AND code = #{code}
        """)
    ApiConnectorEndpoint findByCode(@Param("connectorPid") String connectorPid,
                                     @Param("code") String code);

    @Select("SELECT * FROM ab_api_connector_endpoint WHERE connector_pid = #{connectorPid}")
    List<ApiConnectorEndpoint> findByConnector(@Param("connectorPid") String connectorPid);

    @Delete("DELETE FROM ab_api_connector_endpoint WHERE connector_pid = #{connectorPid}")
    int deleteByConnector(@Param("connectorPid") String connectorPid);
}
