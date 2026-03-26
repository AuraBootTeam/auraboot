package com.auraboot.framework.bpm.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Request body for approve/reject actions.
 */
@Data
public class ApprovalActionRequest {
    private String comment;
    private Map<String, Object> formData;
    /** Base64-encoded PNG signature image */
    private String signature;
    /** Attachment file references [{fileId, fileName, fileSize, url}] */
    private List<Map<String, Object>> attachments;
}
