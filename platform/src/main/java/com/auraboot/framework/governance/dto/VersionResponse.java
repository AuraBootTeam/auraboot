package com.auraboot.framework.governance.dto;

import lombok.Data;

import java.util.Date;
import java.util.Map;

/**
 * Response DTO for version history entries.
 */
@Data
public class VersionResponse {

    private String pid;
    private String entityType;
    private String entityPid;
    private Integer versionNumber;
    private Map<String, Object> snapshotData;
    private String changeRequestPid;
    private String createdByPid;
    private String comment;
    private Date createdAt;
}
