package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.Map;

/**
 * Field fork request DTO
 * Used when forking a field to create a variant
 */
@Data
public class FieldForkRequest {

    /**
     * New field code (required)
     * Must be unique within tenant
     */
    private String newCode;

    /**
     * Semantic type (optional)
     * Can modify semantic type in fork
     */
    private String semanticType;

    /**
     * Feature configuration (optional)
     * Can modify feature config in fork
     */
    private Map<String, Object> feature;

    /**
     * Dictionary code (optional)
     * Can modify dictionary binding in fork
     */
    private String dictCode;

    /**
     * Fork reason (required)
     * Explanation for why this fork is needed
     */
    private String forkReason;

    /**
     * Replace in current model (optional, default: false)
     * If true, replace original field with forked field in current model binding
     */
    private Boolean replaceInCurrentModel;

    /**
     * Current model PID (required if replaceInCurrentModel is true)
     * The model where the binding should be replaced
     */
    private String currentModelPid;
}
