package com.auraboot.framework.aisearch.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Readable global-search candidates plus the caller's current preference. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlobalSearchCandidates {

    private List<ModelCandidate> models;

    private GlobalSearchPreference preference;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelCandidate {
        private String modelCode;
        private String modelLabel;
        private boolean enabled;
    }
}
