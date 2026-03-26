package com.auraboot.framework.meta.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * Evidence Record entity.
 * Tracks individual pieces of evidence collected for a decision subject.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Data
@TableName("ab_evidence_record")
public class EvidenceRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("subject_type")
    private String subjectType;

    @TableField("subject_id")
    private String subjectId;

    @TableField("stage")
    private String stage;

    @TableField("evidence_code")
    private String evidenceCode;

    @TableField(value = "evidence_data", jdbcType = JdbcType.OTHER,
            typeHandler = JsonbStringTypeHandler.class)
    private String evidenceData;

    @TableField("source")
    private String source;

    @TableField("collected_at")
    private Instant collectedAt;

    @TableField("created_at")
    private Instant createdAt;
}
