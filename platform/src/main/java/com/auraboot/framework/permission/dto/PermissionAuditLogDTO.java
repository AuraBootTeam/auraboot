package com.auraboot.framework.permission.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Public response DTO for permission audit entries.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PermissionAuditLogDTO {

    private Long id;
    private Long tenantId;
    private Long memberId;
    private String resourceCode;
    private String actionCode;
    private String recordPid;
    private Boolean result;
    private String reason;
    private List<Object> evaluationTrace;
    private Instant createdAt;
}
