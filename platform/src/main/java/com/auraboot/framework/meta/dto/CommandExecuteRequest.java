package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.Map;

/**
 * Command Execute Request
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class CommandExecuteRequest {

    /**
     * Command payload data
     */
    private Map<String, Object> payload;

    /**
     * Client request ID for idempotency
     */
    private String clientRequestId;

    /**
     * Optional operation type hint (CREATE/UPDATE/DELETE)
     */
    private String operationType;

    /**
     * Optional target record ID (for UPDATE/DELETE)
     */
    private String targetRecordId;

    /**
     * Expected row version for optimistic locking (optional)
     */
    private Integer expectedVersion;
}
