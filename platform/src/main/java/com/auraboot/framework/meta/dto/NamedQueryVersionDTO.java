package com.auraboot.framework.meta.dto;

import com.auraboot.framework.meta.entity.NamedQueryPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DTO for a named query version snapshot.
 */
@Data
public class NamedQueryVersionDTO {

    private String pid;
    private String queryCode;
    private Integer versionNo;
    private String fromSql;
    private JsonNode baseWhere;
    private JsonNode defaultOrder;
    private JsonNode fieldsSnapshot;
    private NamedQueryPolicy policy;
    private String description;
    private String status;
    private LocalDateTime publishedAt;
    private Long publishedBy;
    private LocalDateTime createdAt;
}
