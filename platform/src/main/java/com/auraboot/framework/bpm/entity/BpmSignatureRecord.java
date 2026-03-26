package com.auraboot.framework.bpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * BPM signature record entity.
 * Tracks digital/handwritten signatures on BPM documents.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_bpm_signature_record", autoResultMap = true)
public class BpmSignatureRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;
    private Long tenantId;
    private String documentId;
    private String processInstanceId;
    private String taskId;
    private Long signerUserId;
    private String signatureType;       // HANDWRITTEN, DIGITAL, SEAL

    @TableField(typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> signPosition;

    private String certificateSn;
    private Instant signedAt;
    private Instant createdAt;
}
