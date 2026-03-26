package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.SodViolationLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * Mapper for ab_sod_violation_log table.
 * Provides standard CRUD and custom queries for violation history.
 *
 * @author AuraBoot Team
 * @since 6.2.0
 */
@Mapper
public interface SodViolationLogMapper extends BaseMapper<SodViolationLog> {

    /**
     * Find violations within a time range for the current tenant.
     * Note: tenant_id filter is automatically added by TenantLineInterceptor.
     */
    @Select("""
        SELECT * FROM ab_sod_violation_log
        WHERE created_at >= #{startTime} AND created_at <= #{endTime}
        ORDER BY created_at DESC
        """)
    List<SodViolationLog> findByTimeRange(@Param("startTime") Instant startTime,
                                           @Param("endTime") Instant endTime);

    /**
     * Find violations by actor.
     */
    @Select("""
        SELECT * FROM ab_sod_violation_log
        WHERE actor_id = #{actorId}
        ORDER BY created_at DESC
        """)
    List<SodViolationLog> findByActor(@Param("actorId") Long actorId);

    /**
     * Find violations by entity type and entity id.
     */
    @Select("""
        SELECT * FROM ab_sod_violation_log
        WHERE entity_type = #{entityType} AND entity_id = #{entityId}
        ORDER BY created_at DESC
        """)
    List<SodViolationLog> findByEntity(@Param("entityType") String entityType,
                                        @Param("entityId") Long entityId);
}
