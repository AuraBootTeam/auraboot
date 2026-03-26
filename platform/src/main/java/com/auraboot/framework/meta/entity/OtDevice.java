package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * OT (Operational Technology) device entity.
 *
 * <p>Represents a manufacturing equipment device (AOI, ICT, FCT, SMT pick-and-place,
 * reflow oven, etc.) integrated with the ERP system for automated data collection.
 * Stores connection configuration, data mapping, and device status.
 *
 * <p>Supported protocols: OPCUA, MQTT, MODBUS, REST_API, FILE_WATCH, SECS_GEM.
 * The REST_API webhook approach is the default; other protocol adapters are
 * extensible via the connection_config JSONB.
 *
 * @since 5.3.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_ot_device")
public class OtDevice {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("device_code")
    private String deviceCode;

    @TableField("device_name")
    private String deviceName;

    /** AOI, ICT, FCT, SMT_PP, REFLOW, WAVE_SOLDER, SPI, XRAY, LASER_MARK */
    @TableField("device_type")
    private String deviceType;

    /** OPCUA, MQTT, MODBUS, REST_API, FILE_WATCH, SECS_GEM */
    @TableField("protocol")
    private String protocol;

    /** Connection configuration: { host, port, topic, path, etc. }, stored as JSONB */
    @TableField("connection_config")
    private String connectionConfig;

    /** Field mapping from device data to model fields, stored as JSONB */
    @TableField("data_mapping")
    private String dataMapping;

    /** Target model code to write data to */
    @TableField("target_model_code")
    private String targetModelCode;

    @TableField("polling_interval_ms")
    private Integer pollingIntervalMs;

    /** ONLINE, OFFLINE, ERROR, MAINTENANCE */
    @TableField("status")
    private String status;

    @TableField("last_heartbeat")
    private Instant lastHeartbeat;

    @TableField("enabled")
    private Boolean enabled;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableLogic
    @TableField("deleted_flag")
    private Boolean deletedFlag;
}
