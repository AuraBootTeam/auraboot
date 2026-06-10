package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.decision.ast.DataType;
import com.auraboot.framework.decision.ast.Operand;
import com.auraboot.framework.decision.ast.Operator;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.dto.DecisionTableAnalysisDTO;
import com.auraboot.framework.decision.table.DecisionTable;
import com.auraboot.framework.decision.table.HitPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionTableAnalysisServiceImplTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DecisionTableAnalysisServiceImpl service = new DecisionTableAnalysisServiceImpl(mapper);

    @Test
    void analyzeDetectsFiniteDomainGapsConflictsAndUnreachableRules() {
        DecisionTable table = new DecisionTable(HitPolicy.UNIQUE,
                List.of(new DecisionTable.Input("tier", "Tier",
                        new Operand.PathOperand(Scope.RECORD, "data.tier", DataType.ENUM),
                        List.of("GOLD", "SILVER"))),
                List.of(new DecisionTable.Output("route", "Route", DataType.STRING)),
                List.of(
                        new DecisionTable.Rule("gold-a", 10,
                                Map.of("tier", new DecisionTable.Cell(Operator.EQ, "GOLD")),
                                Map.of("route", "manager")),
                        new DecisionTable.Rule("gold-b", 20,
                                Map.of("tier", new DecisionTable.Cell(Operator.EQ, "GOLD")),
                                Map.of("route", "director")),
                        new DecisionTable.Rule("bronze", 30,
                                Map.of("tier", new DecisionTable.Cell(Operator.EQ, "BRONZE")),
                                Map.of("route", "support"))),
                Map.of());

        DecisionTableAnalysisDTO result = service.analyze(mapper.valueToTree(table));

        assertThat(result.getValid()).isFalse();
        assertThat(result.getMetrics().isFiniteDomainComplete()).isTrue();
        assertThat(result.getMetrics().getFiniteCombinationCount()).isEqualTo(2);
        assertThat(result.getMetrics().getGapCount()).isEqualTo(1);
        assertThat(result.getMetrics().getOverlapCount()).isEqualTo(1);
        assertThat(result.getMetrics().getConflictCount()).isEqualTo(1);
        assertThat(result.getMetrics().getUnreachableRuleCount()).isEqualTo(1);
        assertThat(result.getErrors()).anyMatch(issue -> "DMN_CONFLICT".equals(issue.getCode())
                && issue.getRuleIds().containsAll(List.of("gold-a", "gold-b")));
        assertThat(result.getWarnings()).anyMatch(issue -> "DMN_GAP".equals(issue.getCode())
                && "SILVER".equals(issue.getInputCombination().get("tier")));
        assertThat(result.getWarnings()).anyMatch(issue -> "DMN_UNREACHABLE_RULE".equals(issue.getCode())
                && issue.getRuleIds().contains("bronze"));
    }

    @Test
    void analyzeNormalizesFrontendScopePathInputs() throws Exception {
        String frontendModel = """
            { "hitPolicy":"FIRST",
              "inputs":[
                {"id":"flag","label":"Flag","scope":"record","path":"data.flag","dataType":"boolean"}],
              "outputs":[{"id":"route","label":"Route","dataType":"string"}],
              "rules":[
                {"ruleId":"true-row","when":{"flag":{"feel":"true"}},"then":{"route":"yes"}},
                {"ruleId":"false-row","when":{"flag":{"feel":"false"}},"then":{"route":"no"}}] }
            """;

        DecisionTableAnalysisDTO result = service.analyze(mapper.readTree(frontendModel));

        assertThat(result.getValid()).isTrue();
        assertThat(result.getMetrics().isFiniteDomainComplete()).isTrue();
        assertThat(result.getMetrics().getGapCount()).isZero();
        assertThat(result.getMetrics().getUnreachableRuleCount()).isZero();
    }

    @Test
    void analyzeExplainsContinuousNumericCoverageGaps() {
        DecisionTable table = new DecisionTable(HitPolicy.FIRST,
                List.of(new DecisionTable.Input("amount", "Amount",
                        new Operand.PathOperand(Scope.RECORD, "data.amount", DataType.DECIMAL))),
                List.of(new DecisionTable.Output("route", "Route", DataType.STRING)),
                List.of(
                        new DecisionTable.Rule("low", 10,
                                Map.of("amount", new DecisionTable.Cell(Operator.EQ, "", "< 10")),
                                Map.of("route", "low")),
                        new DecisionTable.Rule("high", 20,
                                Map.of("amount", new DecisionTable.Cell(Operator.EQ, "", "> 20")),
                                Map.of("route", "high"))),
                Map.of());

        DecisionTableAnalysisDTO result = service.analyze(mapper.valueToTree(table));

        assertThat(result.getValid()).isTrue();
        assertThat(result.getWarnings()).anyMatch(issue -> "DMN_CONTINUOUS_DOMAIN".equals(issue.getCode()));
        DecisionTableAnalysisDTO.Issue gap = result.getWarnings().stream()
                .filter(issue -> "DMN_CONTINUOUS_GAP".equals(issue.getCode()))
                .findFirst()
                .orElseThrow();
        assertThat(gap.getInputCombination()).containsEntry("input", "amount");
        assertThat(gap.getMetadata()).containsEntry("input", "amount");
        assertThat(String.valueOf(gap.getMetadata().get("gapRanges"))).contains("[10..20]");
    }

    @Test
    void analyzeValidatesPriorityOutputValuesAgainstDeclaredOrder() {
        DecisionTable table = new DecisionTable(HitPolicy.PRIORITY,
                List.of(new DecisionTable.Input("flag", "Flag",
                        new Operand.PathOperand(Scope.RECORD, "data.flag", DataType.BOOLEAN))),
                List.of(new DecisionTable.Output("route", "Route", DataType.STRING,
                        List.of("HIGH", "LOW"))),
                List.of(
                        new DecisionTable.Rule("allowed", 10,
                                Map.of("flag", new DecisionTable.Cell(Operator.EQ, true)),
                                Map.of("route", "HIGH")),
                        new DecisionTable.Rule("missing-priority", 20,
                                Map.of("flag", new DecisionTable.Cell(Operator.EQ, false)),
                                Map.of("route", "MEDIUM"))),
                Map.of());

        DecisionTableAnalysisDTO result = service.analyze(mapper.valueToTree(table));

        assertThat(result.getValid()).isFalse();
        assertThat(result.getWarnings()).anyMatch(issue -> "DMN_PRIORITY_EXPLANATION".equals(issue.getCode())
                && issue.getMetadata().get("priorityOrder").equals(List.of("HIGH", "LOW")));
        assertThat(result.getErrors()).anyMatch(issue -> "DMN_PRIORITY_OUTPUT_VALUE".equals(issue.getCode())
                && issue.getRuleIds().contains("missing-priority")
                && "MEDIUM".equals(issue.getMetadata().get("value")));
    }

    @Test
    void analyzeValidatesCollectAggregationNumericOutputs() {
        DecisionTable table = new DecisionTable(HitPolicy.COLLECT, DecisionTable.CollectAggregation.SUM,
                List.of(new DecisionTable.Input("flag", "Flag",
                        new Operand.PathOperand(Scope.RECORD, "data.flag", DataType.BOOLEAN))),
                List.of(new DecisionTable.Output("score", "Score", DataType.DECIMAL)),
                List.of(
                        new DecisionTable.Rule("numeric", 10,
                                Map.of("flag", new DecisionTable.Cell(Operator.EQ, true)),
                                Map.of("score", "3.5")),
                        new DecisionTable.Rule("not-numeric", 20,
                                Map.of("flag", new DecisionTable.Cell(Operator.EQ, false)),
                                Map.of("score", "N/A"))),
                Map.of());

        DecisionTableAnalysisDTO result = service.analyze(mapper.valueToTree(table));

        assertThat(result.getValid()).isFalse();
        assertThat(result.getWarnings()).anyMatch(issue -> "DMN_COLLECT_AGGREGATION_EXPLANATION".equals(issue.getCode())
                && "SUM".equals(issue.getMetadata().get("aggregation")));
        assertThat(result.getErrors()).anyMatch(issue -> "DMN_COLLECT_AGGREGATION_VALUE".equals(issue.getCode())
                && issue.getRuleIds().contains("not-numeric"));
    }
}
