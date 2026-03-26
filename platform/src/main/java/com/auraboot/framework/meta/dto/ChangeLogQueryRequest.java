package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * Request DTO for querying change logs.
 *
 * @since 5.1.0
 */
@Data
public class ChangeLogQueryRequest {

    private String modelCode;
    private String recordId;
    private String operation;   // CREATE / UPDATE / DELETE (filter)
    private int pageNum = 1;
    private int pageSize = 20;
}
