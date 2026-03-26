package com.auraboot.framework.email.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * A single step in an email sequence with its delay and template.
 *
 * @since 6.5.0
 */
@Data
@TableName("ab_email_sequence_step")
public class EmailSequenceStep {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("sequence_id")
    private Long sequenceId;

    /** 1-based ordering within the sequence. */
    @TableField("step_order")
    private Integer stepOrder;

    /** Number of days to wait after the previous step (or enrollment) before sending. */
    @TableField("delay_days")
    private Integer delayDays;

    /** Mustache/Freemarker subject template string. */
    @TableField("subject_template")
    private String subjectTemplate;

    /** Mustache/Freemarker body template string (HTML). */
    @TableField("body_template")
    private String bodyTemplate;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
