package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.OtDataLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

/**
 * Mapper for OtDataLog entity.
 *
 * <p>Note: ab_ot_data_log has no deleted_flag (no soft delete), so
 * raw @Select queries do not need deleted_flag filters.
 *
 * @since 5.3.0
 */
@Mapper
public interface OtDataLogMapper extends BaseMapper<OtDataLog> {

    @Select("""
        SELECT * FROM ab_ot_data_log
        WHERE tenant_id = #{tenantId} AND device_id = #{deviceId}
          AND timestamp >= #{start} AND timestamp <= #{end}
        ORDER BY timestamp DESC
        """)
    List<OtDataLog> findByDeviceAndDateRange(@Param("tenantId") Long tenantId,
                                              @Param("deviceId") Long deviceId,
                                              @Param("start") Instant start,
                                              @Param("end") Instant end);

    @Select("""
        SELECT * FROM ab_ot_data_log
        WHERE tenant_id = #{tenantId} AND device_id = #{deviceId}
        ORDER BY timestamp DESC
        LIMIT #{limit}
        """)
    List<OtDataLog> findRecentByDevice(@Param("tenantId") Long tenantId,
                                        @Param("deviceId") Long deviceId,
                                        @Param("limit") int limit);

    @Select("""
        SELECT * FROM ab_ot_data_log
        WHERE tenant_id = #{tenantId} AND status = #{status}
        ORDER BY timestamp DESC
        LIMIT #{limit}
        """)
    List<OtDataLog> findByStatus(@Param("tenantId") Long tenantId,
                                  @Param("status") String status,
                                  @Param("limit") int limit);

    @Select("""
        SELECT COUNT(*) FROM ab_ot_data_log
        WHERE tenant_id = #{tenantId} AND device_id = #{deviceId}
          AND status = #{status}
        """)
    long countByDeviceAndStatus(@Param("tenantId") Long tenantId,
                                 @Param("deviceId") Long deviceId,
                                 @Param("status") String status);
}
