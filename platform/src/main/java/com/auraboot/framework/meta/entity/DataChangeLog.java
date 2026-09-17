package com.auraboot.framework.meta.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * Data change log entity.
 * Records field-level changes for dynamic CRUD operations.
 *
 * @since 5.1.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "ab_data_change_log", autoResultMap = true)
public class DataChangeLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("model_code")
    private String modelCode;

    @TableField("record_id")
    private String recordPid;

    @JsonIgnore
    @Schema(hidden = true)
    public String getRecordId() {
        return recordPid;
    }

    public void setRecordId(String recordId) {
        this.recordPid = recordId;
    }

    /**
     * Operation type: CREATE / UPDATE / DELETE.
     */
    @TableField("operation")
    private String operation;

    @TableField("changed_by")
    private Long changedBy;

    /**
     * Display name of the actor, resolved at query time (ab_user.nick_name /
     * user_name). Not a DB column — populated by read paths that return logs
     * to clients, so timelines can show a readable actor instead of a raw id.
     */
    @TableField(exist = false)
    private String changedByName;

    @TableField("changed_at")
    private Instant changedAt;

    @TableField("command_code")
    private String commandCode;

    @TableField("client_request_id")
    private String clientRequestId;

    /**
     * Change details as JSON array: [{field, oldValue, newValue, fieldLabel}].
     */
    @TableField(value = "changes", typeHandler = JsonbStringTypeHandler.class)
    private String changes;

    /**
     * Full record snapshot before change.
     */
    @TableField(value = "snapshot_before", typeHandler = JsonbStringTypeHandler.class)
    private String snapshotBefore;

    /**
     * Full record snapshot after change.
     */
    @TableField(value = "snapshot_after", typeHandler = JsonbStringTypeHandler.class)
    private String snapshotAfter;
}
