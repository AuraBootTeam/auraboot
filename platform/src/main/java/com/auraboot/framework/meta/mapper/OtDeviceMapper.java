package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.OtDevice;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

/**
 * Mapper for OtDevice entity.
 *
 * @since 5.3.0
 */
@Mapper
public interface OtDeviceMapper extends BaseMapper<OtDevice> {

    @Select("""
        SELECT * FROM ab_ot_device
        WHERE tenant_id = #{tenantId}
          AND deleted_flag = FALSE
        ORDER BY device_code
        """)
    List<OtDevice> findByTenantId(@Param("tenantId") Long tenantId);

    @Select("""
        SELECT * FROM ab_ot_device
        WHERE tenant_id = #{tenantId} AND device_code = #{deviceCode}
          AND deleted_flag = FALSE
        """)
    OtDevice findByCode(@Param("tenantId") Long tenantId,
                         @Param("deviceCode") String deviceCode);

    @Select("""
        SELECT * FROM ab_ot_device
        WHERE tenant_id = #{tenantId} AND device_type = #{deviceType}
          AND enabled = TRUE
          AND deleted_flag = FALSE
        ORDER BY device_code
        """)
    List<OtDevice> findEnabledByType(@Param("tenantId") Long tenantId,
                                      @Param("deviceType") String deviceType);

    @Select("""
        SELECT * FROM ab_ot_device
        WHERE tenant_id = #{tenantId} AND status = #{status}
          AND deleted_flag = FALSE
        ORDER BY device_code
        """)
    List<OtDevice> findByStatus(@Param("tenantId") Long tenantId,
                                 @Param("status") String status);

    @Update("""
        UPDATE ab_ot_device
        SET status = #{status}, updated_at = now()
        WHERE id = #{id} AND tenant_id = #{tenantId}
          AND deleted_flag = FALSE
        """)
    int updateStatus(@Param("id") Long id,
                      @Param("tenantId") Long tenantId,
                      @Param("status") String status);

    @Update("""
        UPDATE ab_ot_device
        SET last_heartbeat = #{heartbeat}, status = 'online', updated_at = now()
        WHERE id = #{id} AND tenant_id = #{tenantId}
          AND deleted_flag = FALSE
        """)
    int updateHeartbeat(@Param("id") Long id,
                         @Param("tenantId") Long tenantId,
                         @Param("heartbeat") Instant heartbeat);
}
