package com.auraboot.framework.aisearch.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Unified cross-model global search result.
 * <p>
 * Records are raw dynamic rows (identical shape to the dynamic list API) so
 * downstream rendering — including any field-level masking wired at the data
 * layer — applies uniformly to list and search output.
 *
 * @author AuraBoot Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlobalSearchResult {

    private String keyword;

    /**
     * True when candidate models remained but the maxModels budget was hit.
     */
    private boolean truncated;

    private List<Group> groups;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Group {
        private String modelCode;
        private String modelLabel;
        private Long total;
        private List<Map<String, Object>> records;
    }
}
