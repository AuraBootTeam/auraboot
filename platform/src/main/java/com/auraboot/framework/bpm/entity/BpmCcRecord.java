package com.auraboot.framework.bpm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.auraboot.framework.bpm.typehandler.JsonListLongTypeHandler;
import com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * BPM CC (carbon copy) record.
 * Maps to table: ab_bpm_cc_record
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_bpm_cc_record", autoResultMap = true)
public class BpmCcRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** ULID unique business key */
    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("process_instance_id")
    private String processInstanceId;

    @TableField("task_id")
    private String taskId;

    @TableField("sender_id")
    private Long senderId;

    @TableField(value = "receiver_user_ids", typeHandler = JsonListLongTypeHandler.class)
    private List<Long> receiverUserIds;

    @TableField("comment")
    private String comment;

    @TableField(value = "read_state", typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> readState;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("deleted_flag")
    @TableLogic
    private Boolean deletedFlag;
}
