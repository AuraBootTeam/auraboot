package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * OT device data ingestion log entity.
 *
 * <p>Records each data point received from a manufacturing device. Stores raw
 * data, parsed data, processing status, and any errors. Used for auditing,
 * diagnostics, and replay of device data.
 *
 * <p>Status lifecycle: RECEIVED → PROCESSED / FAILED / IGNORED
 *
 * @since 5.3.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_ot_data_log")
public class OtDataLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("device_id")
    private Long deviceId;

    @TableField("timestamp")
    private Instant timestamp;

    /** Raw device data, stored as JSONB */
    @TableField("raw_data")
    private String rawData;

    /** Parsed structured data, stored as JSONB */
    @TableField("parsed_data")
    private String parsedData;

    /** RECEIVED, PROCESSED, FAILED, IGNORED */
    @TableField("status")
    private String status;

    @TableField("target_record_id")
    private Long targetRecordId;

    @TableField("error_message")
    private String errorMessage;

    @TableField("processing_time_ms")
    private Integer processingTimeMs;
}
