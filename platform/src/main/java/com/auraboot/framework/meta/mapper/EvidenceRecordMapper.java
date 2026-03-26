package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.EvidenceRecord;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

/**
 * Evidence Record Mapper.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Mapper
public interface EvidenceRecordMapper {

    @Insert("""
        INSERT INTO ab_evidence_record
        (tenant_id, subject_type, subject_id, stage, evidence_code, evidence_data, source, collected_at, created_at)
        VALUES
        (#{tenantId}, #{subjectType}, #{subjectId}, #{stage}, #{evidenceCode},
         #{evidenceData, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{source}, #{collectedAt}, #{createdAt})
        ON CONFLICT (tenant_id, subject_type, subject_id, stage, evidence_code) DO NOTHING
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIdempotent(EvidenceRecord record);

    @Select("""
        SELECT * FROM ab_evidence_record
        WHERE tenant_id = #{tenantId} AND subject_type = #{subjectType}
        AND subject_id = #{subjectId} AND stage = #{stage}
        ORDER BY collected_at
        """)
    List<EvidenceRecord> findBySubject(@Param("tenantId") Long tenantId,
                                        @Param("subjectType") String subjectType,
                                        @Param("subjectId") String subjectId,
                                        @Param("stage") String stage);

    @Select("""
        SELECT COUNT(*) FROM ab_evidence_record
        WHERE tenant_id = #{tenantId} AND subject_type = #{subjectType}
        AND subject_id = #{subjectId} AND stage = #{stage}
        """)
    int countBySubject(@Param("tenantId") Long tenantId,
                       @Param("subjectType") String subjectType,
                       @Param("subjectId") String subjectId,
                       @Param("stage") String stage);

    @Select("""
        SELECT DISTINCT e.tenant_id, e.subject_type, e.subject_id, e.stage, MIN(e.collected_at) as first_evidence_at
        FROM ab_evidence_record e
        WHERE e.tenant_id = #{tenantId}
        AND e.collected_at < #{cutoffTime}
        GROUP BY e.tenant_id, e.subject_type, e.subject_id, e.stage
        HAVING COUNT(*) < (
            SELECT jsonb_array_length(d.required_evidence)
            FROM ab_decision_definition d
            WHERE d.tenant_id = e.tenant_id AND d.subject_type = e.subject_type AND d.stage = e.stage
            AND d.status = 'published' AND d.is_current = TRUE AND d.deleted_flag = false
            LIMIT 1
        )
        LIMIT #{limit}
        """)
    List<java.util.Map<String, Object>> findIncompleteEvidenceSubjects(@Param("tenantId") Long tenantId,
                                                                        @Param("cutoffTime") Instant cutoffTime,
                                                                        @Param("limit") int limit);
}
