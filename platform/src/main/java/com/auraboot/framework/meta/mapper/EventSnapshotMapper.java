package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.EventSnapshot;
import org.apache.ibatis.annotations.*;

/**
 * Event Snapshot Mapper.
 * Provides operations for the ab_event_snapshot table.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Mapper
public interface EventSnapshotMapper {

    @Insert("INSERT INTO ab_event_snapshot " +
            "(tenant_id, aggregate_type, aggregate_id, version, state, metadata, created_at) " +
            "VALUES " +
            "(#{tenantId}, #{aggregateType}, #{aggregateId}, #{version}, " +
            "#{state, jdbcType=OTHER, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}::jsonb, " +
            "#{metadata, jdbcType=OTHER, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}::jsonb, " +
            "#{createdAt}) " +
            "ON CONFLICT (tenant_id, aggregate_type, aggregate_id, version) DO NOTHING")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSnapshot(EventSnapshot snapshot);

    @Select("SELECT * FROM ab_event_snapshot " +
            "WHERE tenant_id = #{tenantId} " +
            "AND aggregate_type = #{aggregateType} " +
            "AND aggregate_id = #{aggregateId} " +
            "ORDER BY version DESC " +
            "LIMIT 1")
    EventSnapshot findLatestSnapshot(@Param("tenantId") Long tenantId,
                                      @Param("aggregateType") String aggregateType,
                                      @Param("aggregateId") String aggregateId);

    @Delete("DELETE FROM ab_event_snapshot " +
            "WHERE tenant_id = #{tenantId} " +
            "AND aggregate_type = #{aggregateType} " +
            "AND aggregate_id = #{aggregateId} " +
            "AND version < #{keepVersion}")
    int deleteOlderSnapshots(@Param("tenantId") Long tenantId,
                              @Param("aggregateType") String aggregateType,
                              @Param("aggregateId") String aggregateId,
                              @Param("keepVersion") int keepVersion);
}
