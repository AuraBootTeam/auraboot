package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * ViewModel configuration validation result.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ViewModelValidationResult {

    private Boolean valid;
    private List<String> errors;
    private List<String> warnings;

    public static ViewModelValidationResult success() {
        return ViewModelValidationResult.builder()
                .valid(true)
                .errors(List.of())
                .warnings(List.of())
                .build();
    }

    public static ViewModelValidationResult failure(List<String> errors) {
        return ViewModelValidationResult.builder()
                .valid(false)
                .errors(errors)
                .warnings(List.of())
                .build();
    }

    public static ViewModelValidationResult withWarnings(List<String> warnings) {
        return ViewModelValidationResult.builder()
                .valid(true)
                .errors(List.of())
                .warnings(warnings)
                .build();
    }
}
