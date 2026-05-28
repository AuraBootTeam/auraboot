package com.auraboot.framework.connector.mapper;

import com.auraboot.framework.connector.entity.AbConnectorCdcEngine;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.time.Instant;

/**
 * Mapper for {@link AbConnectorCdcEngine}.
 *
 * @since 5.3.0
 */
@Mapper
public interface AbConnectorCdcEngineMapper extends BaseMapper<AbConnectorCdcEngine> {

    @Select("SELECT * FROM ab_connector_cdc_engine WHERE pid = #{pid}")
    AbConnectorCdcEngine findByPid(@Param("pid") String pid);

    @Select("SELECT * FROM ab_connector_cdc_engine WHERE connector_pid = #{connectorPid}")
    AbConnectorCdcEngine findByConnectorPid(@Param("connectorPid") String connectorPid);

    @Update("""
        UPDATE ab_connector_cdc_engine
        SET heartbeat_at = #{heartbeatAt}, status = #{status}
        WHERE pid = #{pid}
        """)
    int updateHeartbeat(@Param("pid") String pid,
                        @Param("status") String status,
                        @Param("heartbeatAt") Instant heartbeatAt);
}
