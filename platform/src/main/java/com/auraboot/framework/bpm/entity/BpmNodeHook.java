package com.auraboot.framework.bpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_bpm_node_hook", autoResultMap = true)
public class BpmNodeHook {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;
    private Long tenantId;
    private String processKey;
    private String nodeId;
    private String hookType;        // PRE_CHECK or POST_ACTION
    private Integer executionOrder;

    @TableField(typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> hookConfig;

    private String failStrategy;    // BLOCK, WARN, SKIP
    private Boolean async;
    private Boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;

    @TableLogic
    private Boolean deletedFlag;
}
