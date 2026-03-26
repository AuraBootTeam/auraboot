package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.BpmAuditRecordEntity;
import com.auraboot.framework.bpm.mapper.BpmAuditRecordMapper;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * BPM AI-assisted analysis service.
 * Provides rule-based intelligence for assignee suggestion, bottleneck detection,
 * SLA risk prediction, and process generation from natural language.
 * <p>
 * Current implementation uses heuristic/statistical analysis.
 * Can be extended with LLM API integration by injecting an LlmClient.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BpmAiService {

    private final BpmAuditRecordMapper auditRecordMapper;
    private final BpmProcessDefinitionMapper processDefinitionMapper;
    private final SlaRecordService slaRecordService;

    /**
     * Suggest assignees based on workload distribution and historical task completion.
     * Analyzes recent audit records to find users who complete tasks fastest for the given process.
     *
     * @param context map containing processKey, nodeId, and optionally candidateUserIds
     * @return suggestion result with ranked user list
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> suggestAssignee(Map<String, Object> context) {
        String processKey = (String) context.get("processKey");
        if (processKey == null) {
            return Map.of("suggestions", List.of(), "message", "processKey is required");
        }

        // Query recent task completions for this process
        List<BpmAuditRecordEntity> records = auditRecordMapper.findByProcessDefinitionKey(processKey);
        if (records.isEmpty()) {
            return Map.of("suggestions", List.of(), "message", "No historical data for process: " + processKey);
        }

        // Count task completions per user
        Map<String, Long> completionCount = records.stream()
                .filter(r -> "task_complete".equals(r.getOperation()))
                .filter(r -> r.getUserId() != null)
                .collect(Collectors.groupingBy(BpmAuditRecordEntity::getUserId, Collectors.counting()));

        // Rank by completion count (most experienced first)
        List<Map<String, Object>> suggestions = completionCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(entry -> Map.<String, Object>of(
                        "userId", entry.getKey(),
                        "completedTasks", entry.getValue(),
                        "confidence", Math.min(1.0, entry.getValue() / 10.0)
                ))
                .toList();

        return Map.of(
                "suggestions", suggestions,
                "processKey", processKey,
                "basedOn", "historical_task_completion",
                "dataPoints", records.size()
        );
    }

    /**
     * Analyze process bottlenecks by examining audit trail timing.
     * Identifies nodes where tasks spend the most time pending.
     *
     * @param processKey the process definition key
     * @return bottleneck analysis result
     */
    public Map<String, Object> analyzeBottleneck(String processKey) {
        List<BpmAuditRecordEntity> records = auditRecordMapper.findByProcessDefinitionKey(processKey);
        if (records.isEmpty()) {
            return Map.of("processKey", processKey, "bottlenecks", List.of(), "message", "No audit data available");
        }

        // Group events by processInstanceId and analyze timing gaps
        Map<String, List<BpmAuditRecordEntity>> byInstance = records.stream()
                .filter(r -> r.getProcessInstanceId() != null)
                .collect(Collectors.groupingBy(BpmAuditRecordEntity::getProcessInstanceId));

        // Track operation frequency and identify slow operations
        Map<String, List<Long>> operationDurations = new LinkedHashMap<>();

        for (List<BpmAuditRecordEntity> instanceRecords : byInstance.values()) {
            instanceRecords.sort(Comparator.comparing(
                    r -> r.getCreatedAt() != null ? r.getCreatedAt() : Instant.MIN));

            for (int i = 1; i < instanceRecords.size(); i++) {
                BpmAuditRecordEntity prev = instanceRecords.get(i - 1);
                BpmAuditRecordEntity curr = instanceRecords.get(i);

                if (prev.getCreatedAt() != null && curr.getCreatedAt() != null) {
                    long durationMinutes = Duration.between(prev.getCreatedAt(), curr.getCreatedAt()).toMinutes();
                    String key = prev.getOperation() + " → " + curr.getOperation();
                    operationDurations.computeIfAbsent(key, k -> new ArrayList<>()).add(durationMinutes);
                }
            }
        }

        // Find transitions with highest average duration
        List<Map<String, Object>> bottlenecks = operationDurations.entrySet().stream()
                .filter(e -> e.getValue().size() >= 2) // need at least 2 data points
                .map(e -> {
                    List<Long> durations = e.getValue();
                    double avg = durations.stream().mapToLong(Long::longValue).average().orElse(0);
                    long max = durations.stream().mapToLong(Long::longValue).max().orElse(0);
                    return Map.<String, Object>of(
                            "transition", e.getKey(),
                            "avgDurationMinutes", Math.round(avg * 100.0) / 100.0,
                            "maxDurationMinutes", max,
                            "occurrences", durations.size()
                    );
                })
                .sorted(Comparator.<Map<String, Object>, Double>comparing(
                        m -> ((Number) m.get("avgDurationMinutes")).doubleValue()).reversed())
                .limit(5)
                .toList();

        return Map.of(
                "processKey", processKey,
                "bottlenecks", bottlenecks,
                "instancesAnalyzed", byInstance.size(),
                "totalEvents", records.size()
        );
    }

    /**
     * Predict SLA risk for a running process instance.
     * Examines current SLA records and historical completion rates.
     *
     * @param processInstanceId the process instance ID
     * @return risk assessment with score and factors
     */
    public Map<String, Object> predictSlaRisk(String processInstanceId) {
        var slaRecords = slaRecordService.findByProcessInstance(processInstanceId);
        if (slaRecords.isEmpty()) {
            return Map.of(
                    "processInstanceId", processInstanceId,
                    "riskScore", 0.0,
                    "riskLevel", "none",
                    "factors", List.of("No SLA configured for this instance")
            );
        }

        List<Map<String, Object>> factors = new ArrayList<>();
        double maxRisk = 0.0;

        for (var sla : slaRecords) {
            if (sla.getDeadlineTime() == null || sla.getStartTime() == null) continue;

            Duration totalDuration = Duration.between(sla.getStartTime(), sla.getDeadlineTime());
            Duration elapsed = Duration.between(sla.getStartTime(), Instant.now());

            if (totalDuration.isZero() || totalDuration.isNegative()) continue;

            double progressRatio = (double) elapsed.toMillis() / totalDuration.toMillis();
            double risk;
            String riskNote;

            if ("overdue".equals(sla.getStatus())) {
                risk = 1.0;
                riskNote = "SLA already breached";
            } else if (progressRatio > 0.9) {
                risk = 0.9;
                riskNote = "Over 90% of SLA time consumed";
            } else if (progressRatio > 0.7) {
                risk = 0.6;
                riskNote = "Over 70% of SLA time consumed";
            } else if (progressRatio > 0.5) {
                risk = 0.3;
                riskNote = "Over 50% of SLA time consumed";
            } else {
                risk = progressRatio * 0.5;
                riskNote = "Within normal range";
            }

            factors.add(Map.of(
                    "nodeId", sla.getNodeId() != null ? sla.getNodeId() : "unknown",
                    "slaConfigId", sla.getSlaConfigId() != null ? sla.getSlaConfigId() : "",
                    "progress", Math.round(progressRatio * 100.0) / 100.0,
                    "risk", Math.round(risk * 100.0) / 100.0,
                    "note", riskNote
            ));

            maxRisk = Math.max(maxRisk, risk);
        }

        String riskLevel;
        if (maxRisk >= 0.8) riskLevel = "high";
        else if (maxRisk >= 0.5) riskLevel = "medium";
        else if (maxRisk > 0) riskLevel = "low";
        else riskLevel = "none";

        return Map.of(
                "processInstanceId", processInstanceId,
                "riskScore", Math.round(maxRisk * 100.0) / 100.0,
                "riskLevel", riskLevel,
                "factors", factors,
                "slaCount", slaRecords.size()
        );
    }

    /**
     * Generate a basic process definition JSON from natural language description.
     * Uses keyword extraction to suggest node types and flow structure.
     *
     * @param description natural language process description
     * @return generated BPMN JSON with nodes and edges
     */
    public Map<String, Object> generateProcess(String description) {
        if (description == null || description.isBlank()) {
            return Map.of("bpmnJson", Map.of(), "message", "Description is required");
        }

        // Simple keyword-based process generation
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        // Always start with startEvent
        nodes.add(Map.of("id", "start_1", "type", "startEvent", "label", "Start",
                "position", Map.of("x", 100, "y", 200)));

        // Extract steps from description (split by common delimiters)
        String[] steps = description.split("[,，;；。.\\n]+");
        List<String> validSteps = Arrays.stream(steps)
                .map(String::trim)
                .filter(s -> !s.isEmpty() && s.length() > 1)
                .limit(8)
                .toList();

        String previousNodeId = "start_1";
        int x = 300;

        for (int i = 0; i < validSteps.size(); i++) {
            String step = validSteps.get(i);
            String nodeId = "task_" + (i + 1);
            String nodeType = inferNodeType(step);
            String label = step.length() > 20 ? step.substring(0, 20) + "..." : step;

            nodes.add(Map.of("id", nodeId, "type", nodeType, "label", label,
                    "position", Map.of("x", x, "y", 200)));

            edges.add(Map.of("id", previousNodeId + "_" + nodeId,
                    "source", previousNodeId, "target", nodeId));

            previousNodeId = nodeId;
            x += 200;
        }

        // End event
        String endId = "end_1";
        nodes.add(Map.of("id", endId, "type", "endEvent", "label", "End",
                "position", Map.of("x", x, "y", 200)));
        edges.add(Map.of("id", previousNodeId + "_" + endId,
                "source", previousNodeId, "target", endId));

        return Map.of(
                "bpmnJson", Map.of("nodes", nodes, "edges", edges),
                "nodeCount", nodes.size(),
                "description", description,
                "message", "Generated basic process flow from description"
        );
    }

    private String inferNodeType(String stepDescription) {
        String lower = stepDescription.toLowerCase();
        if (lower.contains("审批") || lower.contains("approve") || lower.contains("review")
                || lower.contains("确认") || lower.contains("签") || lower.contains("check")) {
            return "userTask";
        }
        if (lower.contains("通知") || lower.contains("notify") || lower.contains("发送")
                || lower.contains("send") || lower.contains("邮件") || lower.contains("email")) {
            return "serviceTask";
        }
        if (lower.contains("等待") || lower.contains("wait") || lower.contains("接收")
                || lower.contains("receive")) {
            return "receiveTask";
        }
        if (lower.contains("判断") || lower.contains("条件") || lower.contains("if")
                || lower.contains("branch") || lower.contains("分支")) {
            return "exclusiveGateway";
        }
        // Default to userTask
        return "userTask";
    }
}
