package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.decision.ast.DataType;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Operand;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.dto.DecisionTableAnalysisDTO;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.service.DecisionTableAnalysisService;
import com.auraboot.framework.decision.table.DecisionTable;
import com.auraboot.framework.decision.table.DecisionTableEvaluator;
import com.auraboot.framework.decision.table.DecisionTableFeel;
import com.auraboot.framework.decision.table.DecisionTableJson;
import com.auraboot.framework.decision.table.HitPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Finite-domain decision-table analysis for DMN V2. Free-form numeric/string domains remain
 * heuristic follow-ons; this service only marks completeness as exhaustive when every input has a
 * finite domain.
 */
@Service
public class DecisionTableAnalysisServiceImpl implements DecisionTableAnalysisService {

    private static final int MAX_FINITE_COMBINATIONS = 512;

    private final ObjectMapper mapper;
    private final DecisionTableEvaluator evaluator;

    public DecisionTableAnalysisServiceImpl(ObjectMapper mapper) {
        this.mapper = mapper;
        this.evaluator = new DecisionTableEvaluator();
    }

    @Override
    public DecisionTableAnalysisDTO analyze(JsonNode model) {
        DecisionTableAnalysisDTO dto = new DecisionTableAnalysisDTO();
        DecisionTable table;
        try {
            table = mapper.treeToValue(DecisionTableJson.normalizeEditorModel(mapper, model), DecisionTable.class);
        } catch (Exception e) {
            dto.addError(DecisionTableAnalysisDTO.Issue.of("DMN_TABLE_PARSE", "ERROR", List.of(), Map.of(),
                    "Invalid decision table model: " + e.getMessage()));
            return dto;
        }

        dto.getMetrics().setRuleCount(table.rules().size());
        addFeelDiagnostics(table, dto);
        Map<String, List<Object>> domains = finiteDomains(table);
        boolean finiteComplete = domains.size() == table.inputs().size() && !domains.isEmpty();
        addDomainDiagnostics(table, domains, dto);
        List<Map<String, Object>> combinations = finiteComplete ? combinations(domains) : List.of();
        if (combinations.size() > MAX_FINITE_COMBINATIONS) {
            finiteComplete = false;
            combinations = List.of();
            dto.addWarning(DecisionTableAnalysisDTO.Issue.of("DMN_ANALYSIS_LIMIT", "WARNING", List.of(), Map.of(),
                    "Finite-domain combination count exceeds " + MAX_FINITE_COMBINATIONS + "; exhaustive gap analysis skipped"));
        }
        dto.getMetrics().setFiniteDomainComplete(finiteComplete);
        dto.getMetrics().setFiniteCombinationCount(combinations.size());

        Set<String> reachable = new LinkedHashSet<>();
        int gapCount = 0;
        int overlapCount = 0;
        int conflictCount = 0;

        for (Map<String, Object> combination : combinations) {
            List<DecisionTable.Rule> matched = matchedRules(table, combination, dto);
            matched.forEach(rule -> reachable.add(rule.ruleId()));
            if (matched.isEmpty()) {
                gapCount += 1;
                dto.addWarning(DecisionTableAnalysisDTO.Issue.of("DMN_GAP", "WARNING", List.of(), combination,
                        "No rule matches finite-domain input combination"));
            } else if (matched.size() > 1) {
                overlapCount += 1;
                List<String> ruleIds = matched.stream().map(DecisionTable.Rule::ruleId).toList();
                if (hasConflictingOutputs(matched)) {
                    conflictCount += 1;
                    dto.addError(DecisionTableAnalysisDTO.Issue.of("DMN_CONFLICT", "ERROR", ruleIds, combination,
                            "Rules overlap with different outputs"));
                } else if (table.hitPolicy() == HitPolicy.UNIQUE) {
                    dto.addError(DecisionTableAnalysisDTO.Issue.of("DMN_OVERLAP", "ERROR", ruleIds, combination,
                            "UNIQUE hitPolicy has overlapping rules"));
                } else {
                    dto.addWarning(DecisionTableAnalysisDTO.Issue.of("DMN_OVERLAP", "WARNING", ruleIds, combination,
                            "Rules overlap for this finite-domain input combination"));
                }
            }
        }

        for (DecisionTable.Rule rule : table.rules()) {
            if (finiteComplete && !reachable.contains(rule.ruleId())) {
                dto.addWarning(DecisionTableAnalysisDTO.Issue.of("DMN_UNREACHABLE_RULE", "WARNING",
                        List.of(rule.ruleId()), Map.of(), "Rule is unreachable for the declared finite input domains"));
            }
        }

        dto.getMetrics().setGapCount(gapCount);
        dto.getMetrics().setOverlapCount(overlapCount);
        dto.getMetrics().setConflictCount(conflictCount);
        int unreachableRuleCount = 0;
        if (finiteComplete) {
            for (DecisionTable.Rule rule : table.rules()) {
                if (!reachable.contains(rule.ruleId())) {
                    unreachableRuleCount += 1;
                }
            }
        }
        dto.getMetrics().setUnreachableRuleCount(unreachableRuleCount);
        return dto;
    }

    private void addFeelDiagnostics(DecisionTable table, DecisionTableAnalysisDTO dto) {
        Map<String, DecisionTable.Input> inputs = new LinkedHashMap<>();
        for (DecisionTable.Input input : table.inputs()) {
            inputs.put(input.id(), input);
        }
        for (DecisionTable.Rule rule : table.rules()) {
            for (Map.Entry<String, DecisionTable.Cell> entry : rule.when().entrySet()) {
                DecisionTable.Cell cell = entry.getValue();
                if (!DecisionTableFeel.hasText(cell)) {
                    continue;
                }
                DecisionTable.Input input = inputs.get(entry.getKey());
                DataType dataType = inputDataType(input);
                try {
                    DecisionTableFeel.parse(cell.feel(), dataType);
                } catch (IllegalArgumentException e) {
                    dto.addError(DecisionTableAnalysisDTO.Issue.of("DMN_FEEL_PARSE", "ERROR",
                            List.of(rule.ruleId()), Map.of("input", entry.getKey()), e.getMessage()));
                    continue;
                }
                if (looksUnsupportedFeel(cell.feel())) {
                    dto.addWarning(DecisionTableAnalysisDTO.Issue.of("DMN_UNSUPPORTED_FEEL", "WARNING",
                            List.of(rule.ruleId()), Map.of("input", entry.getKey()),
                            "FEEL cell uses expressions outside the platform unary-test subset; convert it to -, null, comparison, range, or comma-list syntax before relying on runtime equality semantics"));
                }
            }
        }
    }

    private void addDomainDiagnostics(DecisionTable table, Map<String, List<Object>> domains,
                                      DecisionTableAnalysisDTO dto) {
        for (DecisionTable.Input input : table.inputs()) {
            if (domains.containsKey(input.id())) {
                continue;
            }
            DataType dataType = inputDataType(input);
            if (dataType != null && (dataType.isNumeric()
                    || dataType == DataType.DATE
                    || dataType == DataType.TIME
                    || dataType == DataType.DATETIME
                    || dataType == DataType.DURATION)) {
                dto.addWarning(DecisionTableAnalysisDTO.Issue.of("DMN_CONTINUOUS_DOMAIN", "WARNING",
                        List.of(), Map.of("input", input.id()),
                        "Input '" + input.id() + "' is " + dataType.code()
                                + " without allowedValues, so completeness/gap analysis is non-exhaustive"));
            }
        }
    }

    private DataType inputDataType(DecisionTable.Input input) {
        if (input != null && input.expr() instanceof Operand.PathOperand path) {
            return path.dataType();
        }
        return null;
    }

    private boolean looksUnsupportedFeel(String feel) {
        String text = feel == null ? "" : feel.trim();
        if (text.isEmpty() || "-".equals(text)) {
            return false;
        }
        String lower = text.toLowerCase();
        if ("null".equals(lower) || "not(null)".equals(lower) || "not null".equals(lower)) {
            return false;
        }
        if (text.matches("^\\[\\s*(.+?)\\s*\\.\\.\\s*(.+?)\\s*]$")) {
            return false;
        }
        java.util.regex.Matcher comparison = java.util.regex.Pattern.compile("^(>=|<=|>|<|!=|=)\\s*(.+)$").matcher(text);
        if (comparison.matches()) {
            return looksLikeFeelExpression(comparison.group(2));
        }
        if (text.contains(",")) {
            for (String part : text.split(",")) {
                if (looksLikeFeelExpression(part)) {
                    return true;
                }
            }
            return false;
        }
        return looksLikeFeelExpression(text);
    }

    private boolean looksLikeFeelExpression(String text) {
        String candidate = text == null ? "" : text.trim();
        String lower = candidate.toLowerCase();
        return candidate.contains("(")
                || candidate.contains(")")
                || lower.matches(".*\\b(if|then|else|and|or|between|date|time|duration|not)\\b.*");
    }

    private Map<String, List<Object>> finiteDomains(DecisionTable table) {
        Map<String, List<Object>> domains = new LinkedHashMap<>();
        for (DecisionTable.Input input : table.inputs()) {
            if (!input.allowedValues().isEmpty()) {
                domains.put(input.id(), input.allowedValues());
            } else if (input.expr() != null && input.expr().dataType() == DataType.BOOLEAN) {
                domains.put(input.id(), List.of(Boolean.TRUE, Boolean.FALSE));
            }
        }
        return domains;
    }

    private List<Map<String, Object>> combinations(Map<String, List<Object>> domains) {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(new LinkedHashMap<>());
        for (Map.Entry<String, List<Object>> entry : domains.entrySet()) {
            List<Map<String, Object>> next = new ArrayList<>();
            for (Map<String, Object> existing : result) {
                for (Object value : entry.getValue()) {
                    Map<String, Object> copy = new LinkedHashMap<>(existing);
                    copy.put(entry.getKey(), value);
                    next.add(copy);
                }
            }
            result = next;
        }
        return result;
    }

    private List<DecisionTable.Rule> matchedRules(DecisionTable table, Map<String, Object> combination,
                                                  DecisionTableAnalysisDTO dto) {
        DecisionContext context = contextFor(table, combination);
        List<DecisionTable.Rule> matched = new ArrayList<>();
        for (DecisionTable.Rule rule : table.rules()) {
            DecisionTable single = new DecisionTable(HitPolicy.FIRST, table.inputs(), table.outputs(),
                    List.of(rule), Map.of());
            DecisionTableEvaluator.Result result = evaluator.evaluate(single, context);
            if (result.status() == DecisionStatus.MATCHED) {
                matched.add(rule);
            } else if (result.status() == DecisionStatus.ERROR) {
                dto.addError(DecisionTableAnalysisDTO.Issue.of("DMN_RULE_EVAL_ERROR", "ERROR",
                        List.of(rule.ruleId()), combination, String.join("; ", result.errors())));
            }
        }
        return matched;
    }

    private DecisionContext contextFor(DecisionTable table, Map<String, Object> combination) {
        Map<Scope, Object> scopes = new HashMap<>();
        for (DecisionTable.Input input : table.inputs()) {
            if (!(input.expr() instanceof Operand.PathOperand path)) {
                continue;
            }
            Object value = combination.get(input.id());
            @SuppressWarnings("unchecked")
            Map<String, Object> scope = (Map<String, Object>) scopes.computeIfAbsent(path.scope(), ignored -> new LinkedHashMap<>());
            putPath(scope, path.path(), value);
        }
        return DecisionContext.of(scopes);
    }

    @SuppressWarnings("unchecked")
    private void putPath(Map<String, Object> root, String path, Object value) {
        String[] segments = path == null ? new String[0] : path.split("\\.");
        Map<String, Object> cursor = root;
        for (int i = 0; i < segments.length; i++) {
            if (i == segments.length - 1) {
                cursor.put(segments[i], value);
            } else {
                cursor = (Map<String, Object>) cursor.computeIfAbsent(segments[i], ignored -> new LinkedHashMap<>());
            }
        }
    }

    private boolean hasConflictingOutputs(List<DecisionTable.Rule> matched) {
        Set<Map<String, Object>> outputs = new LinkedHashSet<>();
        for (DecisionTable.Rule rule : matched) {
            outputs.add(rule.then());
        }
        return outputs.size() > 1 && outputs.stream().anyMatch(Objects::nonNull);
    }
}
