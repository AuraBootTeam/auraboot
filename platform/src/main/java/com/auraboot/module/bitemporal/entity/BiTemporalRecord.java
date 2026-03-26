package com.auraboot.module.bitemporal.entity;

import com.auraboot.framework.application.typehandler.JsonNodeTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Bi-temporal record entity.
 * Tracks entity state across both valid time (business time) and transaction time (system time).
 *
 * @since 6.0.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "ab_bitemporal_record", autoResultMap = true)
public class BiTemporalRecord {

    public static final LocalDateTime INFINITY = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("entity_type")
    private String entityType;

    @TableField("entity_id")
    private String entityId;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("valid_from")
    private LocalDateTime validFrom;

    @TableField("valid_to")
    private LocalDateTime validTo;

    @TableField("tx_from")
    private LocalDateTime txFrom;

    @TableField("tx_to")
    private LocalDateTime txTo;

    @TableField(value = "payload", typeHandler = JsonNodeTypeHandler.class)
    private JsonNode payload;

    @TableField("created_by")
    private Long createdBy;

    @TableField("version_no")
    private Integer versionNo;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted_flag")
    private Boolean deletedFlag;
}
