package com.auraboot.framework.scheduler.dto;

import lombok.Data;

/**
 * Request DTO for querying task execution logs.
 *
 * @since 5.1.0
 */
@Data
public class TaskLogQueryRequest {

    private String taskPid;
    private String status;
    private int pageNum = 1;
    private int pageSize = 20;
}
