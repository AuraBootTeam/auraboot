package com.auraboot.framework.decision.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Stateless DMN XML import/export result.
 */
@Data
public class DecisionTableDmnXmlDTO {

    private Boolean valid = true;

    private String dmnXml;

    private JsonNode model;

    private List<Issue> errors = new ArrayList<>();

    private List<Issue> warnings = new ArrayList<>();

    @Data
    public static class Issue {
        private String code;
        private String message;

        public static Issue of(String code, String message) {
            Issue issue = new Issue();
            issue.setCode(code);
            issue.setMessage(message);
            return issue;
        }
    }

    public void addError(String code, String message) {
        errors.add(Issue.of(code, message));
        valid = false;
    }

    public void addWarning(String code, String message) {
        warnings.add(Issue.of(code, message));
    }
}
