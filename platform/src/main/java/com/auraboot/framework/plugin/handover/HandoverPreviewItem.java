package com.auraboot.framework.plugin.handover;

import com.baomidou.mybatisplus.annotation.*;
import com.auraboot.framework.application.typehandler.JsonNodeTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
@TableName(value="ab_plugin_handover_item", autoResultMap=true)
public class HandoverPreviewItem {
    @TableId(type=IdType.AUTO) private Long id;
    private String batchPid;
    private Long tenantId;
    private Integer ordinal;
    private String resourceType;
    private String resourceCode;
    private String resourcePid;
    private String targetPluginPid;
    private String action;
    @TableField(typeHandler=JsonNodeTypeHandler.class) private JsonNode snapshot;
}
