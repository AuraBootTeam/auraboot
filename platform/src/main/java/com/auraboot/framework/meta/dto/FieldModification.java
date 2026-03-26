package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Field modification DTO
 * Represents proposed changes to a field for impact analysis
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldModification {

    /**
     * Field PID being modified
     */
    private String fieldPid;

    /**
     * New code (if changing)
     */
    private String newCode;

    /**
     * New data type (if changing)
     */
    private String newDataType;

    /**
     * New semantic type (if changing)
     */
    private String newSemanticType;

    /**
     * New feature configuration (if changing)
     */
    private Map<String, Object> newFeature;

    /**
     * New reference target (if changing)
     */
    private Map<String, Object> newRefTarget;

    /**
     * New UI schema (if changing)
     */
    private Map<String, Object> newUiSchema;

    /**
     * New query schema (if changing)
     */
    private Map<String, Object> newQuerySchema;

    /**
     * New rule schema (if changing)
     */
    private Map<String, Object> newRuleSchema;

    /**
     * New extension properties (if changing)
     */
    private Map<String, Object> newExtension;

    /**
     * Modification description
     */
    private String modificationDescription;
}
