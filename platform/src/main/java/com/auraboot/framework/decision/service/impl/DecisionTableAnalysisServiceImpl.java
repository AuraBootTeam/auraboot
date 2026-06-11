package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.decision.ast.DataType;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Operand;
import com.auraboot.framework.decision.ast.Operator;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
        long startedAt = System.nanoTime();
        DecisionTableAnalysisDTO dto = new DecisionTableAnalysisDTO();
        DecisionTable table;
        try {
            table = mapper.treeToValue(DecisionTableJson.normalizeEditorModel(mapper, model), DecisionTable.class);
        } catch (Exception e) {
            dto.addError(DecisionTableAnalysisDTO.Issue.of("DMN_TABLE_PARSE", "ERROR", List.of(), Map.of(),
                    "Invalid decision table model: " + e.getMessage()));
            dto.getMetrics().setAnalysisDurationMs(elapsedMillis(startedAt));
            return dto;
        }

        dto.getMetrics().setRuleCount(table.rules().size());
        addFeelDiagnostics(table, dto);
        addHitPolicyDiagnostics(table, dto);
        Map<String, List<Object>> domains = finiteDomains(table);
        dto.getMetrics().setFiniteInputCount(domains.size());
        dto.getMetrics().setContinuousInputCount(continuousInputs(table, domains).size());
        boolean finiteComplete = domains.size() == table.inputs().size() && !domains.isEmpty();
        addDomainDiagnostics(table, domains, dto);
        addComplexInputProofDiagnostics(table, domains, finiteComplete, dto);
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
        dto.getMetrics().setAnalysisDurationMs(elapsedMillis(startedAt));
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
                if (!DecisionTableFeel.isSupportedSyntax(cell.feel())) {
                    dto.addWarning(DecisionTableAnalysisDTO.Issue.of("DMN_UNSUPPORTED_FEEL", "WARNING",
                            List.of(rule.ruleId()), Map.of("input", entry.getKey()),
                            "FEEL cell uses expressions outside the platform unary-test subset; convert it to -, null, comparison, range, or comma-list syntax before relying on runtime equality semantics"));
                }
            }
        }
    }

    private void addHitPolicyDiagnostics(DecisionTable table, DecisionTableAnalysisDTO dto) {
        if (table.hitPolicy() == HitPolicy.PRIORITY) {
            addPriorityDiagnostics(table, dto);
        } else if (table.hitPolicy() == HitPolicy.COLLECT) {
            addCollectDiagnostics(table, dto);
        }
    }

    private void addPriorityDiagnostics(DecisionTable table, DecisionTableAnalysisDTO dto) {
        if (table.outputs().size() != 1) {
            dto.addError(DecisionTableAnalysisDTO.Issue.of("DMN_PRIORITY_SHAPE", "ERROR", List.of(), Map.of(),
                    "PRIORITY hitPolicy requires exactly one output column"));
            return;
        }
        DecisionTable.Output output = table.outputs().get(0);
        if (output.allowedValues().isEmpty()) {
            dto.addError(DecisionTableAnalysisDTO.Issue.of("DMN_PRIORITY_ALLOWED_VALUES", "ERROR", List.of(), Map.of(),
                    Map.of("output", output.id()),
                    "PRIORITY hitPolicy requires output allowedValues ordered highest-first"));
            return;
        }
        dto.addWarning(DecisionTableAnalysisDTO.Issue.of("DMN_PRIORITY_EXPLANATION", "WARNING", List.of(), Map.of(),
                Map.of("output", output.id(), "priorityOrder", output.allowedValues()),
                "PRIORITY chooses the matched rule whose output value appears earliest in allowedValues"));
        for (DecisionTable.Rule rule : table.rules()) {
            Object value = rule.then().get(output.id());
            if (!containsValue(output.allowedValues(), value)) {
                dto.addError(DecisionTableAnalysisDTO.Issue.of("DMN_PRIORITY_OUTPUT_VALUE", "ERROR",
                        List.of(rule.ruleId()), Map.of(),
                        Map.of("output", output.id(), "value", String.valueOf(value),
                                "allowedValues", output.allowedValues()),
                        "PRIORITY rule output is not present in the output allowedValues priority order"));
            }
        }
    }

    private void addCollectDiagnostics(DecisionTable table, DecisionTableAnalysisDTO dto) {
        DecisionTable.CollectAggregation aggregation = table.aggregation();
        if (aggregation == DecisionTable.CollectAggregation.NONE) {
            dto.addWarning(DecisionTableAnalysisDTO.Issue.of("DMN_COLLECT_EXPLANATION", "WARNING", List.of(),
                    Map.of(), Map.of("aggregation", aggregation.name()),
                    "COLLECT without aggregation returns all matched output values as arrays"));
            return;
        }
        if (table.outputs().size() != 1) {
            dto.addError(DecisionTableAnalysisDTO.Issue.of("DMN_COLLECT_AGGREGATION_SHAPE", "ERROR", List.of(),
                    Map.of(), Map.of("aggregation", aggregation.name()),
                    "COLLECT aggregation requires exactly one output column"));
            return;
        }
        DecisionTable.Output output = table.outputs().get(0);
        dto.addWarning(DecisionTableAnalysisDTO.Issue.of("DMN_COLLECT_AGGREGATION_EXPLANATION", "WARNING",
                List.of(), Map.of(),
                Map.of("aggregation", aggregation.name(), "output", output.id()),
                "COLLECT " + aggregation + " aggregates all matched rows for output '" + output.id() + "'"));
        if (aggregation == DecisionTable.CollectAggregation.COUNT) {
            return;
        }
        if (!isNumeric(output.dataType())) {
            dto.addError(DecisionTableAnalysisDTO.Issue.of("DMN_COLLECT_AGGREGATION_OUTPUT", "ERROR", List.of(),
                    Map.of(), Map.of("aggregation", aggregation.name(), "output", output.id()),
                    "COLLECT " + aggregation + " requires a numeric output column"));
            return;
        }
        for (DecisionTable.Rule rule : table.rules()) {
            Object value = rule.then().get(output.id());
            if (toBigDecimal(value) == null) {
                dto.addError(DecisionTableAnalysisDTO.Issue.of("DMN_COLLECT_AGGREGATION_VALUE", "ERROR",
                        List.of(rule.ruleId()), Map.of(),
                        Map.of("aggregation", aggregation.name(), "output", output.id(),
                                "value", String.valueOf(value)),
                        "COLLECT " + aggregation + " output contains a non-numeric rule value"));
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
            if (isContinuous(dataType)) {
                dto.addWarning(DecisionTableAnalysisDTO.Issue.of("DMN_CONTINUOUS_DOMAIN", "WARNING",
                        List.of(), Map.of("input", input.id()),
                        "Input '" + input.id() + "' is " + dataType.code()
                                + " without allowedValues, so completeness/gap analysis is non-exhaustive"));
                addContinuousCoverageDiagnostics(table, input, dataType, dto);
            }
        }
    }

    private void addComplexInputProofDiagnostics(DecisionTable table, Map<String, List<Object>> domains,
                                                 boolean finiteComplete, DecisionTableAnalysisDTO dto) {
        if (table.inputs().size() < 2) {
            return;
        }
        List<String> finiteInputs = table.inputs().stream()
                .map(DecisionTable.Input::id)
                .filter(domains::containsKey)
                .toList();
        List<String> continuousInputs = continuousInputs(table, domains).stream()
                .map(DecisionTable.Input::id)
                .toList();
        if (continuousInputs.isEmpty() && finiteComplete) {
            return;
        }
        String proofMode = continuousInputs.isEmpty()
                ? "FINITE_CARTESIAN_PARTIAL"
                : finiteInputs.isEmpty()
                ? "PER_AXIS_INTERVALS_ONLY"
                : "FINITE_CARTESIAN_PLUS_PER_AXIS_INTERVALS";
        dto.addWarning(DecisionTableAnalysisDTO.Issue.of("DMN_COMPLEX_INPUT_PROOF", "WARNING", List.of(), Map.of(),
                Map.of(
                        "inputCount", table.inputs().size(),
                        "finiteInputs", finiteInputs,
                        "continuousInputs", continuousInputs,
                        "finiteCombinationUpperBound", combinationUpperBound(domains),
                        "maxFiniteCombinations", MAX_FINITE_COMBINATIONS,
                        "proofMode", proofMode
                ),
                "Complex multi-input analysis uses finite Cartesian enumeration plus per-input continuous interval coverage; correlated cross-input FEEL constraints remain non-exhaustive"));
    }

    private List<DecisionTable.Input> continuousInputs(DecisionTable table, Map<String, List<Object>> domains) {
        return table.inputs().stream()
                .filter(input -> !domains.containsKey(input.id()))
                .filter(input -> isContinuous(inputDataType(input)))
                .toList();
    }

    private void addContinuousCoverageDiagnostics(DecisionTable table, DecisionTable.Input input, DataType dataType,
                                                  DecisionTableAnalysisDTO dto) {
        List<ContinuousInterval> covered = new ArrayList<>();
        int unparsedCells = 0;
        for (DecisionTable.Rule rule : table.rules()) {
            List<ContinuousInterval> intervals = intervalsForCell(rule.when().get(input.id()), dataType);
            if (intervals == null) {
                unparsedCells += 1;
                continue;
            }
            covered.addAll(intervals);
        }
        if (covered.isEmpty()) {
            dto.addWarning(DecisionTableAnalysisDTO.Issue.of("DMN_CONTINUOUS_COVERAGE_UNKNOWN", "WARNING",
                    List.of(), Map.of("input", input.id()),
                    Map.of("input", input.id(), "unparsedCells", unparsedCells),
                    "Continuous numeric coverage could not be derived from this input's cells"));
            return;
        }
        List<ContinuousInterval> merged = mergeIntervals(covered);
        List<ContinuousInterval> gaps = gapsBetween(merged);
        if (gaps.isEmpty()) {
            return;
        }
        List<String> gapRanges = gaps.stream().map(interval -> interval.format(dataType)).toList();
        dto.addWarning(DecisionTableAnalysisDTO.Issue.of("DMN_CONTINUOUS_GAP", "WARNING", List.of(),
                Map.of("input", input.id()),
                Map.of("input", input.id(),
                        "inputLabel", input.label(),
                        "dataType", dataType.code(),
                        "coveredRanges", merged.stream().map(interval -> interval.format(dataType)).toList(),
                        "gapRanges", gapRanges,
                        "unparsedCells", unparsedCells),
                "Input '" + input.id() + "' has uncovered continuous " + dataType.code() + " ranges: "
                        + String.join(", ", gapRanges)));
    }

    private DataType inputDataType(DecisionTable.Input input) {
        if (input != null && input.expr() instanceof Operand.PathOperand path) {
            return path.dataType();
        }
        return null;
    }

    private List<ContinuousInterval> intervalsForCell(DecisionTable.Cell cell, DataType dataType) {
        if (cell == null) {
            return List.of(ContinuousInterval.all());
        }
        if (DecisionTableFeel.hasText(cell)) {
            try {
                List<DecisionTableFeel.ParsedTest> tests = DecisionTableFeel.parse(cell.feel(), dataType);
                if (tests.isEmpty()) {
                    return List.of(ContinuousInterval.all());
                }
                List<ContinuousInterval> intervals = new ArrayList<>();
                for (DecisionTableFeel.ParsedTest test : tests) {
                    List<ContinuousInterval> parsed = intervalsForOperator(test.operator(), test.value(), dataType);
                    if (parsed == null) {
                        return null;
                    }
                    intervals.addAll(parsed);
                }
                return intervals;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        if (cell.operator() == null) {
            return null;
        }
        return intervalsForOperator(cell.operator(), cell.value(), dataType);
    }

    private List<ContinuousInterval> intervalsForOperator(Operator operator, Object value, DataType dataType) {
        if (operator == Operator.IS_NULL || operator == Operator.IS_NOT_NULL) {
            return null;
        }
        if (operator == Operator.IN) {
            if (!(value instanceof List<?> values)) {
                return null;
            }
            List<ContinuousInterval> intervals = new ArrayList<>();
            for (Object item : values) {
                BigDecimal point = toAxisValue(item, dataType);
                if (point == null) {
                    return null;
                }
                intervals.add(ContinuousInterval.point(point));
            }
            return intervals;
        }
        if (operator == Operator.BETWEEN) {
            if (!(value instanceof List<?> values) || values.size() != 2) {
                return null;
            }
            BigDecimal lower = toAxisValue(values.get(0), dataType);
            BigDecimal upper = toAxisValue(values.get(1), dataType);
            if (lower == null || upper == null) {
                return null;
            }
            if (lower.compareTo(upper) > 0) {
                BigDecimal tmp = lower;
                lower = upper;
                upper = tmp;
            }
            return List.of(new ContinuousInterval(lower, true, upper, true));
        }
        BigDecimal boundary = toAxisValue(value, dataType);
        if (boundary == null) {
            return null;
        }
        return switch (operator) {
            case EQ -> List.of(ContinuousInterval.point(boundary));
            case NE -> List.of(
                    new ContinuousInterval(null, false, boundary, false),
                    new ContinuousInterval(boundary, false, null, false));
            case GT -> List.of(new ContinuousInterval(boundary, false, null, false));
            case GTE -> List.of(new ContinuousInterval(boundary, true, null, false));
            case LT -> List.of(new ContinuousInterval(null, false, boundary, false));
            case LTE -> List.of(new ContinuousInterval(null, false, boundary, true));
            default -> null;
        };
    }

    private List<ContinuousInterval> mergeIntervals(List<ContinuousInterval> intervals) {
        List<ContinuousInterval> sorted = intervals.stream()
                .sorted(Comparator
                        .comparing((ContinuousInterval interval) -> interval.lower(), Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(ContinuousInterval::lowerInclusive, Comparator.reverseOrder()))
                .toList();
        List<ContinuousInterval> merged = new ArrayList<>();
        for (ContinuousInterval interval : sorted) {
            if (merged.isEmpty()) {
                merged.add(interval);
                continue;
            }
            ContinuousInterval last = merged.get(merged.size() - 1);
            if (last.touches(interval)) {
                merged.set(merged.size() - 1, last.merge(interval));
            } else {
                merged.add(interval);
            }
        }
        return merged;
    }

    private List<ContinuousInterval> gapsBetween(List<ContinuousInterval> intervals) {
        if (intervals.isEmpty()) {
            return List.of(ContinuousInterval.all());
        }
        List<ContinuousInterval> gaps = new ArrayList<>();
        ContinuousInterval first = intervals.get(0);
        if (first.lower() != null) {
            gaps.add(new ContinuousInterval(null, false, first.lower(), !first.lowerInclusive()));
        }
        for (int i = 0; i < intervals.size() - 1; i++) {
            ContinuousInterval current = intervals.get(i);
            ContinuousInterval next = intervals.get(i + 1);
            if (current.upper() != null && next.lower() != null) {
                gaps.add(new ContinuousInterval(current.upper(), !current.upperInclusive(),
                        next.lower(), !next.lowerInclusive()));
            }
        }
        ContinuousInterval last = intervals.get(intervals.size() - 1);
        if (last.upper() != null) {
            gaps.add(new ContinuousInterval(last.upper(), !last.upperInclusive(), null, false));
        }
        return gaps.stream().filter(ContinuousInterval::valid).toList();
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

    private long combinationUpperBound(Map<String, List<Object>> domains) {
        long count = 1;
        for (List<Object> values : domains.values()) {
            int size = Math.max(values.size(), 1);
            if (count > Long.MAX_VALUE / size) {
                return Long.MAX_VALUE;
            }
            count *= size;
            if (count > MAX_FINITE_COMBINATIONS) {
                return count;
            }
        }
        return count;
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

    private boolean containsValue(List<Object> allowedValues, Object value) {
        for (Object allowed : allowedValues) {
            if (Objects.equals(String.valueOf(allowed), String.valueOf(value))) {
                return true;
            }
        }
        return false;
    }

    private boolean isNumeric(DataType dataType) {
        return dataType == DataType.INTEGER || dataType == DataType.DECIMAL;
    }

    private boolean isContinuous(DataType dataType) {
        return dataType != null && (dataType.isNumeric()
                || dataType == DataType.DATE
                || dataType == DataType.TIME
                || dataType == DataType.DATETIME
                || dataType == DataType.DURATION);
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }

    private BigDecimal toAxisValue(Object value, DataType dataType) {
        if (dataType == null || dataType.isNumeric()) {
            return toBigDecimal(value);
        }
        String text = stripLiteralQuotes(value);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return switch (dataType) {
                case DATE -> BigDecimal.valueOf(LocalDate.parse(text).toEpochDay());
                case TIME -> BigDecimal.valueOf(LocalTime.parse(text).toNanoOfDay());
                case DATETIME -> BigDecimal.valueOf(parseInstant(text).toEpochMilli());
                case DURATION -> BigDecimal.valueOf(Duration.parse(text).toNanos());
                default -> toBigDecimal(value);
            };
        } catch (DateTimeParseException | ArithmeticException e) {
            return null;
        }
    }

    private static String stripLiteralQuotes(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.length() >= 2 && ((text.startsWith("\"") && text.endsWith("\""))
                || (text.startsWith("'") && text.endsWith("'")))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private static Instant parseInstant(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
    }

    private static String formatAxisValue(BigDecimal value, DataType dataType) {
        if (value == null) {
            return "";
        }
        if (dataType == DataType.DATE) {
            return LocalDate.ofEpochDay(value.longValue()).toString();
        }
        if (dataType == DataType.TIME) {
            return LocalTime.ofNanoOfDay(value.longValue()).toString();
        }
        if (dataType == DataType.DATETIME) {
            return Instant.ofEpochMilli(value.longValue()).toString();
        }
        if (dataType == DataType.DURATION) {
            return Duration.ofNanos(value.longValue()).toString();
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private record ContinuousInterval(BigDecimal lower, boolean lowerInclusive,
                                      BigDecimal upper, boolean upperInclusive) {
        static ContinuousInterval all() {
            return new ContinuousInterval(null, false, null, false);
        }

        static ContinuousInterval point(BigDecimal value) {
            return new ContinuousInterval(value, true, value, true);
        }

        boolean valid() {
            if (lower == null || upper == null) {
                return true;
            }
            int compared = lower.compareTo(upper);
            return compared < 0 || (compared == 0 && lowerInclusive && upperInclusive);
        }

        boolean touches(ContinuousInterval next) {
            if (upper == null || next.lower == null) {
                return true;
            }
            int compared = upper.compareTo(next.lower);
            return compared > 0 || (compared == 0 && (upperInclusive || next.lowerInclusive));
        }

        ContinuousInterval merge(ContinuousInterval next) {
            if (upper == null || next.upper == null) {
                return new ContinuousInterval(lower, lowerInclusive, null, false);
            }
            int compared = upper.compareTo(next.upper);
            if (compared > 0) {
                return this;
            }
            if (compared < 0) {
                return new ContinuousInterval(lower, lowerInclusive, next.upper, next.upperInclusive);
            }
            return new ContinuousInterval(lower, lowerInclusive, upper, upperInclusive || next.upperInclusive);
        }

        String format(DataType dataType) {
            String left = lowerInclusive ? "[" : "(";
            String right = upperInclusive ? "]" : ")";
            String from = lower == null ? "-inf" : formatAxisValue(lower, dataType);
            String to = upper == null ? "+inf" : formatAxisValue(upper, dataType);
            return left + from + ".." + to + right;
        }
    }
}
