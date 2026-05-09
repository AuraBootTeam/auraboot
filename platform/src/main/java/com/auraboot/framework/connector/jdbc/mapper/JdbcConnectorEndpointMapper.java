package com.auraboot.framework.connector.jdbc.mapper;

import com.auraboot.framework.connector.jdbc.entity.JdbcConnectorEndpoint;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface JdbcConnectorEndpointMapper extends BaseMapper<JdbcConnectorEndpoint> {
    @Select("SELECT * FROM ab_jdbc_connector_endpoint WHERE connector_pid = #{connectorPid} AND code = #{code} LIMIT 1")
    JdbcConnectorEndpoint findByCode(@Param("connectorPid") String connectorPid, @Param("code") String code);

    @Select("SELECT * FROM ab_jdbc_connector_endpoint WHERE connector_pid = #{connectorPid} ORDER BY id ASC")
    List<JdbcConnectorEndpoint> findByConnector(@Param("connectorPid") String connectorPid);

    @Delete("DELETE FROM ab_jdbc_connector_endpoint WHERE connector_pid = #{connectorPid}")
    int deleteByConnector(@Param("connectorPid") String connectorPid);
}
