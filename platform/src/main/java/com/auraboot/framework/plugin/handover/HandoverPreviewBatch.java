package com.auraboot.framework.plugin.handover;

import com.baomidou.mybatisplus.annotation.*;
import com.auraboot.framework.application.typehandler.JsonNodeTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import java.time.Instant;

@Data
@TableName(value="ab_plugin_handover_batch", autoResultMap=true)
public class HandoverPreviewBatch {
    @TableId(type=IdType.AUTO) private Long id;
    private String pid;
    private Long tenantId;
    private String contractId;
    private String contractDigest;
    private String sourcePluginPid;
    private String sourcePluginId;
    private String idempotencyKey;
    private String requestDigest;
    private String previewDigest;
    private String status;
    private Long createdBy;
    private Instant createdAt;
    private Instant expiresAt;
    @TableField(typeHandler=JsonNodeTypeHandler.class) private JsonNode snapshot;
}
