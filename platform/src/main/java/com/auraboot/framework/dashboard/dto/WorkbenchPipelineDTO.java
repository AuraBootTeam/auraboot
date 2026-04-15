package com.auraboot.framework.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Pipeline view of CRM opportunities grouped by stage.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkbenchPipelineDTO {

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Stage {
        private String code;
        private String label;
        private int count;
        private BigDecimal amount;
        private String color;
    }

    private List<Stage> stages;
    private BigDecimal totalAmount;
    private int totalCount;
}
