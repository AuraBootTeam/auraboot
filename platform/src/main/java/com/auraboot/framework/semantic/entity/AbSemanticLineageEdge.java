package com.auraboot.framework.semantic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * Directed edge in the semantic-layer lineage graph.
 *
 * <p>Edge example: {@code metric(total_sales) --depends_on--> measure(order_amount)}.
 * Maintained by SemanticPublishService at YAML import/update time.
 *
 * <p>Used to power {@code /api/semantic/lineage/{pid}} and downstream impact
 * analysis when a metric/model is changed or removed.
 */
@Data
@TableName("ab_semantic_lineage_edge")
public class AbSemanticLineageEdge {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;

    private Long tenantId;

    private String srcNodePid;

    /** model / metric / exposure / source / dimension */
    private String srcNodeType;

    private String dstNodePid;

    private String dstNodeType;

    /** depends_on / input_metric / source / measure_ref */
    private String refType;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableLogic
    private Boolean deletedFlag;
}
