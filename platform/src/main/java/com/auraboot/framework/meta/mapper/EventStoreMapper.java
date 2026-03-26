package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.EventStoreEntry;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Event Store Mapper.
 * Provides operations for the ab_event_store table.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Mapper
public interface EventStoreMapper {

    @Insert("INSERT INTO ab_event_store " +
            "(tenant_id, event_id, event_type, aggregate_type, aggregate_id, " +
            "version, payload, metadata, occurred_at, created_at) " +
            "VALUES " +
            "(#{tenantId}, #{eventId}, #{eventType}, #{aggregateType}, #{aggregateId}, " +
            "#{version}, " +
            "#{payload, jdbcType=OTHER, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}::jsonb, " +
            "#{metadata, jdbcType=OTHER, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}::jsonb, " +
            "#{occurredAt}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertEvent(EventStoreEntry entry);

    @Select("SELECT * FROM ab_event_store " +
            "WHERE tenant_id = #{tenantId} " +
            "AND aggregate_type = #{aggregateType} " +
            "AND aggregate_id = #{aggregateId} " +
            "ORDER BY version ASC")
    List<EventStoreEntry> findAllEvents(@Param("tenantId") Long tenantId,
                                         @Param("aggregateType") String aggregateType,
                                         @Param("aggregateId") String aggregateId);

    @Select("SELECT * FROM ab_event_store " +
            "WHERE tenant_id = #{tenantId} " +
            "AND aggregate_type = #{aggregateType} " +
            "AND aggregate_id = #{aggregateId} " +
            "AND version > #{sinceVersion} " +
            "ORDER BY version ASC")
    List<EventStoreEntry> findEventsSinceVersion(@Param("tenantId") Long tenantId,
                                                  @Param("aggregateType") String aggregateType,
                                                  @Param("aggregateId") String aggregateId,
                                                  @Param("sinceVersion") int sinceVersion);

    @Select("SELECT COALESCE(MAX(version), 0) FROM ab_event_store " +
            "WHERE tenant_id = #{tenantId} " +
            "AND aggregate_type = #{aggregateType} " +
            "AND aggregate_id = #{aggregateId}")
    int findMaxVersion(@Param("tenantId") Long tenantId,
                       @Param("aggregateType") String aggregateType,
                       @Param("aggregateId") String aggregateId);

    @Select("SELECT * FROM ab_event_store " +
            "WHERE tenant_id = #{tenantId} " +
            "AND aggregate_type = #{aggregateType} " +
            "AND aggregate_id = #{aggregateId} " +
            "ORDER BY version DESC " +
            "LIMIT #{limit} OFFSET #{offset}")
    List<EventStoreEntry> findEventsPaginated(@Param("tenantId") Long tenantId,
                                               @Param("aggregateType") String aggregateType,
                                               @Param("aggregateId") String aggregateId,
                                               @Param("limit") int limit,
                                               @Param("offset") int offset);
}
