package com.auraboot.framework.connector.mapper;

import com.auraboot.framework.connector.entity.AbConnectorSyncRun;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for {@link AbConnectorSyncRun}.
 *
 * @since 5.3.0
 */
@Mapper
public interface AbConnectorSyncRunMapper extends BaseMapper<AbConnectorSyncRun> {

    @Select("SELECT * FROM ab_connector_sync_run WHERE pid = #{pid}")
    AbConnectorSyncRun findByPid(@Param("pid") String pid);

    @Select("""
        SELECT * FROM ab_connector_sync_run
        WHERE connector_pid = #{connectorPid}
        ORDER BY started_at DESC
        LIMIT #{limit}
        """)
    List<AbConnectorSyncRun> listRecent(@Param("connectorPid") String connectorPid,
                                        @Param("limit") int limit);

    @Select("""
        SELECT * FROM ab_connector_sync_run
        WHERE status = #{status}
        ORDER BY started_at DESC
        LIMIT #{limit}
        """)
    List<AbConnectorSyncRun> findByStatus(@Param("status") String status,
                                          @Param("limit") int limit);
}
