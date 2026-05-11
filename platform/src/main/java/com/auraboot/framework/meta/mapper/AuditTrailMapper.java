package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.AuditTrail;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * Mapper for ab_audit_trail table.
 * Provides standard CRUD via BaseMapper and custom query methods for
 * chain hashing, integrity verification, and audit trail lookups.
 *
 * @since 6.1.0
 */
@Mapper
public interface AuditTrailMapper extends BaseMapper<AuditTrail> {

    /**
     * Get the maximum sequence number for a given tenant.
     * Returns null if no records exist yet (genesis case).
     */
    @Select("SELECT MAX(sequence_no) FROM ab_audit_trail WHERE tenant_id = #{tenantId}")
    Long getMaxSequenceNo(@Param("tenantId") Long tenantId);

    /**
     * Get the latest audit record for a tenant (by highest sequence_no).
     * Used to retrieve the previous hash for chain linking.
     */
    @Select("SELECT id, tenant_id, sequence_no, event_type, entity_type, entity_id, entity_pid, " +
            "command_code, operation_type, actor_id, actor_name, actor_ip, timestamp, " +
            "previous_hash, record_hash " +
            "FROM ab_audit_trail WHERE tenant_id = #{tenantId} " +
            "ORDER BY sequence_no DESC LIMIT 1")
    AuditTrail getLatestByTenant(@Param("tenantId") Long tenantId);

    /**
     * Get audit records within a sequence range for chain verification.
     * Ordered by sequence_no ascending for sequential hash checking.
     */
    @Select("SELECT * FROM ab_audit_trail WHERE tenant_id = #{tenantId} " +
            "AND sequence_no >= #{fromSeq} AND sequence_no <= #{toSeq} " +
            "ORDER BY sequence_no ASC")
    List<AuditTrail> getBySequenceRange(@Param("tenantId") Long tenantId,
                                         @Param("fromSeq") Long fromSeq,
                                         @Param("toSeq") Long toSeq);

    /**
     * Get audit trail for a specific entity (model + record).
     */
    @Select("SELECT * FROM ab_audit_trail WHERE tenant_id = #{tenantId} " +
            "AND entity_type = #{entityType} AND entity_id = #{entityId} " +
            "ORDER BY sequence_no ASC")
    List<AuditTrail> getByEntity(@Param("tenantId") Long tenantId,
                                  @Param("entityType") String entityType,
                                  @Param("entityId") Long entityId);

    /**
     * Get audit trail for a specific entity PID (model + public record PID).
     */
    @Select("SELECT * FROM ab_audit_trail WHERE tenant_id = #{tenantId} " +
            "AND entity_type = #{entityType} AND entity_pid = #{entityPid} " +
            "ORDER BY sequence_no ASC")
    List<AuditTrail> getByEntityPid(@Param("tenantId") Long tenantId,
                                     @Param("entityType") String entityType,
                                     @Param("entityPid") String entityPid);

    /**
     * Get audit records by actor within a time range.
     */
    @Select("SELECT * FROM ab_audit_trail WHERE tenant_id = #{tenantId} " +
            "AND actor_id = #{actorId} " +
            "AND timestamp >= #{startTime} AND timestamp <= #{endTime} " +
            "ORDER BY sequence_no ASC")
    List<AuditTrail> getByActor(@Param("tenantId") Long tenantId,
                                 @Param("actorId") Long actorId,
                                 @Param("startTime") Instant startTime,
                                 @Param("endTime") Instant endTime);

    /**
     * Get audit records by command code.
     */
    @Select("SELECT * FROM ab_audit_trail WHERE tenant_id = #{tenantId} " +
            "AND command_code = #{commandCode} " +
            "ORDER BY sequence_no ASC")
    List<AuditTrail> getByCommand(@Param("tenantId") Long tenantId,
                                   @Param("commandCode") String commandCode);

    /**
     * Get audit records within a time range for compliance reports.
     */
    @Select("SELECT * FROM ab_audit_trail WHERE tenant_id = #{tenantId} " +
            "AND timestamp >= #{startTime} AND timestamp <= #{endTime} " +
            "ORDER BY sequence_no ASC")
    List<AuditTrail> getByTimeRange(@Param("tenantId") Long tenantId,
                                     @Param("startTime") Instant startTime,
                                     @Param("endTime") Instant endTime);

    /**
     * Count records in a time range (for compliance summary).
     */
    @Select("SELECT COUNT(*) FROM ab_audit_trail WHERE tenant_id = #{tenantId} " +
            "AND timestamp >= #{startTime} AND timestamp <= #{endTime}")
    long countByTimeRange(@Param("tenantId") Long tenantId,
                          @Param("startTime") Instant startTime,
                          @Param("endTime") Instant endTime);

    /**
     * Get the record immediately before a given sequence (for verification boundary).
     */
    @Select("SELECT id, tenant_id, sequence_no, record_hash " +
            "FROM ab_audit_trail WHERE tenant_id = #{tenantId} " +
            "AND sequence_no = #{seqNo} - 1")
    AuditTrail getPreviousRecord(@Param("tenantId") Long tenantId,
                                  @Param("seqNo") Long seqNo);

    /**
     * Get recent activity feed (lightweight — no snapshots/hashes).
     * Supports cursor-based pagination via beforeId.
     */
    @Select("""
        <script>
        SELECT id, tenant_id, event_type, entity_type, entity_id, entity_pid,
               command_code, operation_type, actor_id, actor_name, timestamp, changed_fields
        FROM ab_audit_trail
        WHERE tenant_id = #{tenantId}
        <if test="entityType != null"> AND entity_type = #{entityType} </if>
        <if test="beforeId != null"> AND id &lt; #{beforeId} </if>
        ORDER BY id DESC
        LIMIT #{limit}
        </script>
        """)
    List<AuditTrail> getRecentFeed(@Param("tenantId") Long tenantId,
                                    @Param("entityType") String entityType,
                                    @Param("beforeId") Long beforeId,
                                    @Param("limit") int limit);
}
