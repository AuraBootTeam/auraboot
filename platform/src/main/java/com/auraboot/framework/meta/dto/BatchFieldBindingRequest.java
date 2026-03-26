package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Batch field binding request DTO
 * Used when binding multiple fields to a model at once
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchFieldBindingRequest {

    /**
     * List of field PIDs to bind
     */
    @NotEmpty(message = "Field PIDs list cannot be empty")
    private List<String> fieldPids;

    /**
     * Common configuration to apply to all bindings
     */
    private CommonBindingConfig commonConfig;

    /**
     * Common binding configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CommonBindingConfig {
        /**
         * Required flag
         */
        private Boolean required;

        /**
         * Nullable flag
         */
        private Boolean nullable;

        /**
         * Readonly flag
         */
        private Boolean readonly;

        /**
         * Visible flag
         */
        private Boolean visible;

        /**
         * Editable flag
         */
        private Boolean editable;
    }
}
