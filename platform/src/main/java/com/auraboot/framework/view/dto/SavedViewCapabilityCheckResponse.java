package com.auraboot.framework.view.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
public class SavedViewCapabilityCheckResponse {
    @Schema(nullable = true)
    private String viewType;
    @Schema(nullable = true)
    private String status;
    private List<String> missingFields = new ArrayList<>();
    private List<Reason> reasons = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Reason {
        @Schema(nullable = true)
        private String code;
        @Schema(nullable = true)
        private String field;
        @Schema(nullable = true)
        private String message;
    }
}
