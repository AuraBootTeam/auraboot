package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.DecisionDefinition;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Decision Definition Mapper.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Mapper
public interface DecisionDefinitionMapper extends BaseMapper<DecisionDefinition> {

    @Insert("""
        INSERT INTO ab_decision_definition
        (pid, tenant_id, code, display_name, description, subject_type, stage,
         required_evidence, invariants, outcome_options, auto_adjudicate, extension,
         version, semver, is_current, row_version, status, deleted_flag,
         created_at, updated_at)
        VALUES
        (#{pid}, #{tenantId}, #{code}, #{displayName}, #{description}, #{subjectType}, #{stage},
         #{requiredEvidence, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{invariants, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{outcomeOptions, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{autoAdjudicate},
         #{extension, typeHandler=com.auraboot.framework.application.database.mybatis.ExtensionTypeHandler},
         #{version}, #{semver}, #{isCurrent}, #{rowVersion}, #{status}, #{deletedFlag},
         #{createdAt}, #{updatedAt})
        ON CONFLICT (tenant_id, code, version) DO NOTHING
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIdempotent(DecisionDefinition definition);

    @Select("SELECT * FROM ab_decision_definition WHERE pid = #{pid} AND deleted_flag = false")
    DecisionDefinition findByPid(@Param("pid") String pid);

    @Select("""
        SELECT * FROM ab_decision_definition
        WHERE tenant_id = #{tenantId} AND code = #{code} AND is_current = TRUE AND deleted_flag = false
        """)
    DecisionDefinition findCurrentByCode(@Param("tenantId") Long tenantId, @Param("code") String code);

    @Select("""
        SELECT * FROM ab_decision_definition
        WHERE tenant_id = #{tenantId} AND subject_type = #{subjectType} AND stage = #{stage}
        AND status = 'published' AND is_current = TRUE AND deleted_flag = false
        LIMIT 1
        """)
    DecisionDefinition findBySubjectAndStage(@Param("tenantId") Long tenantId,
                                              @Param("subjectType") String subjectType,
                                              @Param("stage") String stage);

    @Select("""
        SELECT * FROM ab_decision_definition
        WHERE tenant_id = #{tenantId} AND subject_type = #{subjectType}
        AND is_current = TRUE AND deleted_flag = false
        ORDER BY stage, code
        """)
    List<DecisionDefinition> findBySubjectType(@Param("tenantId") Long tenantId,
                                                @Param("subjectType") String subjectType);

    @Select("""
        SELECT * FROM ab_decision_definition
        WHERE tenant_id = #{tenantId} AND status = 'published' AND is_current = TRUE AND deleted_flag = false
        """)
    List<DecisionDefinition> findAllPublished(@Param("tenantId") Long tenantId);

    @Update("UPDATE ab_decision_definition SET is_current = FALSE WHERE tenant_id = #{tenantId} AND code = #{code}")
    int markAsNotCurrent(@Param("tenantId") Long tenantId, @Param("code") String code);

    @Update("""
        UPDATE ab_decision_definition
        SET is_current = TRUE, status = #{status}, updated_at = NOW()
        WHERE id = #{id}
        """)
    int publishById(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE ab_decision_definition SET deleted_flag = TRUE, updated_at = NOW() WHERE pid = #{pid}")
    int softDelete(@Param("pid") String pid);
}
