package com.auraboot.framework.view.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
public class SavedViewCapabilityCheckResponse {
    private String viewType;
    private String status;
    private List<String> missingFields = new ArrayList<>();
    private List<Reason> reasons = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Reason {
        private String code;
        private String field;
        private String message;
    }
}
