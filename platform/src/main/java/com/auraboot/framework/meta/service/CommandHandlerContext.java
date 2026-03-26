package com.auraboot.framework.meta.service;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Context passed to CommandHandler during HANDLER phase execution.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
@Builder
public class CommandHandlerContext {

    private String commandCode;
    private String modelCode;
    private Map<String, Object> payload;
    private String operationType;
    private String targetRecordId;
    private Long tenantId;
    private Long userId;
    private Map<String, Object> fieldMapResults;
    private String ruleConfig;
}
