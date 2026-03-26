package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * EDI transaction log entity.
 *
 * <p>Records each inbound or outbound EDI/cXML message exchange with a trading
 * partner. Stores the raw content, parsed data, processing status, and any errors
 * for auditing and retry purposes.
 *
 * <p>Status lifecycle: PENDING → PROCESSING → COMPLETED / FAILED → ACKNOWLEDGED
 *
 * @since 5.3.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_edi_transaction")
public class EdiTransaction {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("transaction_no")
    private String transactionNo;

    @TableField("partner_id")
    private Long partnerId;

    @TableField("message_type_id")
    private Long messageTypeId;

    /** INBOUND, OUTBOUND */
    @TableField("direction")
    private String direction;

    /** PENDING, PROCESSING, COMPLETED, FAILED, ACKNOWLEDGED */
    @TableField("status")
    private String status;

    /** Original message content */
    @TableField("raw_content")
    private String rawContent;

    /** Parsed structured data, stored as JSONB */
    @TableField("parsed_data")
    private String parsedData;

    @TableField("error_message")
    private String errorMessage;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("related_model_code")
    private String relatedModelCode;

    @TableField("related_record_id")
    private Long relatedRecordId;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField("processed_at")
    private Instant processedAt;

    @TableField("acknowledged_at")
    private Instant acknowledgedAt;
}
