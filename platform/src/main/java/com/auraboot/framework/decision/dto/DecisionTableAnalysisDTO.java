package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DMN decision-table static analysis result.
 */
@Data
public class DecisionTableAnalysisDTO {

    private Boolean valid = true;

    private List<Issue> errors = new ArrayList<>();

    private List<Issue> warnings = new ArrayList<>();

    private Metrics metrics = new Metrics();

    @Data
    public static class Issue {
        private String code;
        private String severity;
        private List<String> ruleIds = List.of();
        private Map<String, Object> inputCombination = Map.of();
        private Map<String, Object> metadata = Map.of();
        private String message;

        public static Issue of(String code, String severity, List<String> ruleIds,
                               Map<String, Object> inputCombination, String message) {
            return of(code, severity, ruleIds, inputCombination, Map.of(), message);
        }

        public static Issue of(String code, String severity, List<String> ruleIds,
                               Map<String, Object> inputCombination, Map<String, Object> metadata,
                               String message) {
            Issue issue = new Issue();
            issue.setCode(code);
            issue.setSeverity(severity);
            issue.setRuleIds(ruleIds == null ? List.of() : ruleIds);
            issue.setInputCombination(inputCombination == null ? Map.of() : inputCombination);
            issue.setMetadata(metadata == null ? Map.of() : metadata);
            issue.setMessage(message);
            return issue;
        }
    }

    @Data
    public static class Metrics {
        private int ruleCount;
        private int gapCount;
        private int overlapCount;
        private int conflictCount;
        private int unreachableRuleCount;
        private int finiteCombinationCount;
        private boolean finiteDomainComplete;
    }

    public void addError(Issue issue) {
        errors.add(issue);
        valid = false;
    }

    public void addWarning(Issue issue) {
        warnings.add(issue);
    }
}
