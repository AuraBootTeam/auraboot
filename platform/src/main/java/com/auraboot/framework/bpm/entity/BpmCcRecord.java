package com.auraboot.framework.bpm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_bpm_cc_record", autoResultMap = true)
public class BpmCcRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

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

    @TableField(value = "receiver_user_ids",
            typeHandler = com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler.class)
    private List<Long> receiverUserIds;

    private String comment;

    @TableField(value = "read_state",
            typeHandler = com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler.class)
    private Map<String, Object> readState;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("deleted_flag")
    @TableLogic
    private Boolean deletedFlag;
}
