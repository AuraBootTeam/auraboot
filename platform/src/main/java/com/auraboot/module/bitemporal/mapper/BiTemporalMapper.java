package com.auraboot.module.bitemporal.mapper;

import com.auraboot.module.bitemporal.entity.BiTemporalRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MyBatis Plus mapper for ab_bitemporal_record.
 * <p>
 * Notes:
 * - Multi-tenant interceptor auto-adds tenant_id — DO NOT add manually.
 * - @Select bypasses MyBatis Plus soft-delete — MUST add deleted_flag filter manually.
 * - autoResultMap = true on entity handles JSONB column mapping.
 *
 * @since 6.0.0
 */
@Mapper
public interface BiTemporalMapper extends BaseMapper<BiTemporalRecord> {

    /**
     * Point-in-time query: find the record valid at a specific business time and system time.
     */
    @Select("""
        SELECT * FROM ab_bitemporal_record
        WHERE entity_type = #{entityType}
          AND entity_id = #{entityId}
          AND valid_from <= #{validTime}
          AND valid_to > #{validTime}
          AND tx_from <= #{txTime}
          AND tx_to > #{txTime}
          AND deleted_flag = FALSE
        ORDER BY tx_from DESC
        LIMIT 1
        """)
    @Results(id = "biTemporalResultMap", value = {
        @Result(column = "payload", property = "payload",
                typeHandler = com.auraboot.framework.application.typehandler.JsonNodeTypeHandler.class)
    })
    BiTemporalRecord findAsOf(@Param("entityType") String entityType,
                              @Param("entityId") String entityId,
                              @Param("validTime") LocalDateTime validTime,
                              @Param("txTime") LocalDateTime txTime);

    /**
     * Find the current version: valid now and latest transaction.
     */
    @Select("""
        SELECT * FROM ab_bitemporal_record
        WHERE entity_type = #{entityType}
          AND entity_id = #{entityId}
          AND valid_from <= NOW()
          AND valid_to > NOW()
          AND tx_to = '9999-12-31 23:59:59'
          AND deleted_flag = FALSE
        ORDER BY tx_from DESC
        LIMIT 1
        """)
    @ResultMap("biTemporalResultMap")
    BiTemporalRecord findCurrent(@Param("entityType") String entityType,
                                 @Param("entityId") String entityId);

    /**
     * Find all versions of an entity, ordered by valid_from and tx_from.
     */
    @Select("""
        SELECT * FROM ab_bitemporal_record
        WHERE entity_type = #{entityType}
          AND entity_id = #{entityId}
          AND deleted_flag = FALSE
        ORDER BY valid_from ASC, tx_from ASC
        """)
    @ResultMap("biTemporalResultMap")
    List<BiTemporalRecord> findHistory(@Param("entityType") String entityType,
                                       @Param("entityId") String entityId);

    /**
     * Find all transaction versions for a specific business time point.
     */
    @Select("""
        SELECT * FROM ab_bitemporal_record
        WHERE entity_type = #{entityType}
          AND entity_id = #{entityId}
          AND valid_from <= #{validTime}
          AND valid_to > #{validTime}
          AND deleted_flag = FALSE
        ORDER BY tx_from ASC
        """)
    @ResultMap("biTemporalResultMap")
    List<BiTemporalRecord> findTimeline(@Param("entityType") String entityType,
                                        @Param("entityId") String entityId,
                                        @Param("validTime") LocalDateTime validTime);

    /**
     * Find all current versions of a given entity type (valid now, latest transaction).
     * Useful for bulk queries like "all BOM_LINE versions effective now".
     */
    @Select("""
        SELECT * FROM ab_bitemporal_record
        WHERE entity_type = #{entityType}
          AND valid_from <= #{validTime}
          AND valid_to > #{validTime}
          AND tx_to = '9999-12-31 23:59:59'
          AND deleted_flag = FALSE
        ORDER BY entity_id ASC
        """)
    @ResultMap("biTemporalResultMap")
    List<BiTemporalRecord> findAllByTypeAsOf(@Param("entityType") String entityType,
                                              @Param("validTime") LocalDateTime validTime);

    /**
     * Close the transaction period of an existing record (for corrections).
     */
    @Update("""
        UPDATE ab_bitemporal_record
        SET tx_to = #{txTo}, updated_at = NOW()
        WHERE id = #{id}
          AND deleted_flag = FALSE
        """)
    int closeTxPeriod(@Param("id") Long id, @Param("txTo") LocalDateTime txTo);
}
