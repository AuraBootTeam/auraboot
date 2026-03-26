package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.FieldChangeLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * Mapper for ab_field_change_log table.
 * Provides standard CRUD via BaseMapper and custom query methods
 * for field-level change audit lookups.
 *
 * @since 6.2.0
 */
@Mapper
public interface FieldChangeLogMapper extends BaseMapper<FieldChangeLog> {

    /**
     * Get all field changes for a specific record on a model, ordered by time descending.
     */
    @Select("SELECT * FROM ab_field_change_log " +
            "WHERE tenant_id = #{tenantId} AND model_code = #{modelCode} AND record_id = #{recordId} " +
            "ORDER BY changed_at DESC")
    List<FieldChangeLog> getByModelAndRecord(@Param("tenantId") Long tenantId,
                                              @Param("modelCode") String modelCode,
                                              @Param("recordId") Long recordId);

    /**
     * Get change history for a specific field on a specific record.
     */
    @Select("SELECT * FROM ab_field_change_log " +
            "WHERE tenant_id = #{tenantId} AND model_code = #{modelCode} " +
            "AND record_id = #{recordId} AND field_code = #{fieldCode} " +
            "ORDER BY changed_at DESC")
    List<FieldChangeLog> getByField(@Param("tenantId") Long tenantId,
                                     @Param("modelCode") String modelCode,
                                     @Param("recordId") Long recordId,
                                     @Param("fieldCode") String fieldCode);

    /**
     * Get all field changes made by a specific actor within a time range.
     */
    @Select("SELECT * FROM ab_field_change_log " +
            "WHERE tenant_id = #{tenantId} AND actor_id = #{actorId} " +
            "AND changed_at >= #{startTime} AND changed_at <= #{endTime} " +
            "ORDER BY changed_at DESC")
    List<FieldChangeLog> getByActor(@Param("tenantId") Long tenantId,
                                     @Param("actorId") Long actorId,
                                     @Param("startTime") Instant startTime,
                                     @Param("endTime") Instant endTime);

    /**
     * Get all field changes for a model within a time range (for reporting).
     */
    @Select("SELECT * FROM ab_field_change_log " +
            "WHERE tenant_id = #{tenantId} AND model_code = #{modelCode} " +
            "AND changed_at >= #{startTime} AND changed_at <= #{endTime} " +
            "ORDER BY changed_at DESC")
    List<FieldChangeLog> getByModelAndTimeRange(@Param("tenantId") Long tenantId,
                                                 @Param("modelCode") String modelCode,
                                                 @Param("startTime") Instant startTime,
                                                 @Param("endTime") Instant endTime);

    /**
     * Count field changes for a model within a time range.
     */
    @Select("SELECT COUNT(*) FROM ab_field_change_log " +
            "WHERE tenant_id = #{tenantId} AND model_code = #{modelCode} " +
            "AND changed_at >= #{startTime} AND changed_at <= #{endTime}")
    long countByModelAndTimeRange(@Param("tenantId") Long tenantId,
                                   @Param("modelCode") String modelCode,
                                   @Param("startTime") Instant startTime,
                                   @Param("endTime") Instant endTime);
}
