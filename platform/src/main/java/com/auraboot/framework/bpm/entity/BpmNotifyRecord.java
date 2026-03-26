package com.auraboot.framework.bpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ab_bpm_notify_record")
public class BpmNotifyRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;
    private Long tenantId;
    private String processInstanceId;
    private String taskId;
    private String notifyType;      // CC or URGE
    private Long senderUserId;
    private Long recipientUserId;
    private String content;
    private Boolean isRead;
    private Instant readAt;
    private Instant createdAt;

    @TableLogic
    private Boolean deletedFlag;
}
