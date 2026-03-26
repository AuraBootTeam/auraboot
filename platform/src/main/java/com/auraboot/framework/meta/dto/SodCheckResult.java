package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result of an SoD (Separation of Duties) check.
 * Indicates whether the check passed, and lists any violations found.
 *
 * @author AuraBoot Team
 * @since 6.2.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SodCheckResult {

    /**
     * Overall outcome: PASSED, WARNED, or BLOCKED.
     */
    private String outcome;

    /**
     * List of violation details (empty if passed).
     */
    private List<SodViolationDetail> violations;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SodViolationDetail {
        private String ruleCode;
        private String ruleName;
        private String conflictingCommand;
        private String enforcement;
        private String entityScope;
        private String message;
    }

    public static SodCheckResult passed() {
        return SodCheckResult.builder()
                .outcome("passed")
                .violations(List.of())
                .build();
    }

    public boolean isPassed() {
        return "passed".equals(outcome);
    }

    public boolean isBlocked() {
        return "blocked".equals(outcome);
    }
}
