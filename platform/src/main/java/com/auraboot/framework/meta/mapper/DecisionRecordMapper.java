package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.DecisionRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * Decision Record Mapper.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Mapper
public interface DecisionRecordMapper {

    @Insert("""
        INSERT INTO ab_decision_record
        (tenant_id, subject_type, subject_id, stage, outcome,
         evidence_summary, invariant_results, trace,
         decided_by, decided_at, created_at)
        VALUES
        (#{tenantId}, #{subjectType}, #{subjectId}, #{stage}, #{outcome},
         #{evidenceSummary, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{invariantResults, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{trace, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{decidedBy}, #{decidedAt}, #{createdAt})
        ON CONFLICT (tenant_id, subject_type, subject_id, stage) DO NOTHING
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIdempotent(DecisionRecord record);

    @Select("""
        SELECT * FROM ab_decision_record
        WHERE tenant_id = #{tenantId} AND subject_type = #{subjectType}
        AND subject_id = #{subjectId} AND stage = #{stage}
        """)
    DecisionRecord findBySubject(@Param("tenantId") Long tenantId,
                                  @Param("subjectType") String subjectType,
                                  @Param("subjectId") String subjectId,
                                  @Param("stage") String stage);

    @Select("""
        SELECT * FROM ab_decision_record
        WHERE tenant_id = #{tenantId} AND subject_type = #{subjectType}
        AND subject_id = #{subjectId}
        ORDER BY decided_at
        """)
    List<DecisionRecord> findBySubjectId(@Param("tenantId") Long tenantId,
                                          @Param("subjectType") String subjectType,
                                          @Param("subjectId") String subjectId);

    @Select("""
        SELECT e.tenant_id, e.subject_type, e.subject_id, e.stage
        FROM ab_evidence_record e
        WHERE e.tenant_id = #{tenantId}
        AND NOT EXISTS (
            SELECT 1 FROM ab_decision_record d
            WHERE d.tenant_id = e.tenant_id AND d.subject_type = e.subject_type
            AND d.subject_id = e.subject_id AND d.stage = e.stage
        )
        GROUP BY e.tenant_id, e.subject_type, e.subject_id, e.stage
        HAVING COUNT(*) >= (
            SELECT jsonb_array_length(dd.required_evidence)
            FROM ab_decision_definition dd
            WHERE dd.tenant_id = e.tenant_id AND dd.subject_type = e.subject_type AND dd.stage = e.stage
            AND dd.status = 'published' AND dd.is_current = TRUE AND dd.deleted_flag = false
            LIMIT 1
        )
        LIMIT #{limit}
        """)
    List<Map<String, Object>> findUndecidedWithCompleteEvidence(@Param("tenantId") Long tenantId,
                                                                 @Param("limit") int limit);
}
