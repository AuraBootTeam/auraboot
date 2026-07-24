package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Critical Path Method (CPM) implementation for WBS-based project scheduling.
 * <p>
 * Algorithm:
 * 1. Build adjacency graph from dependency field (comma-separated PIDs)
 * 2. Topological sort to ensure DAG processing order
 * 3. Forward pass: compute Earliest Start (ES) and Earliest Finish (EF)
 * 4. Backward pass: compute Latest Start (LS) and Latest Finish (LF)
 * 5. Slack = LS - ES; critical path = nodes with slack == 0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CriticalPathService {

    private final DynamicDataMapper dynamicDataMapper;

    /**
     * Compute critical path for a given model filtered by project ID.
     *
     * @param tableName       dynamic table name (e.g. "mt_pm_wbs_node")
     * @param projectIdField  field storing the project FK (e.g. "pm_wbs_project_id")
     * @param projectId       the project record ID to filter by
     * @param durationField   field storing task duration in days (e.g. "pm_wbs_duration_days")
     * @param dependencyField field storing comma-separated predecessor PIDs
     * @return critical path result
     */
    public CriticalPathResult compute(
            String tableName,
            String projectIdField,
            String projectId,
            String durationField,
            String dependencyField) {

        // These arrive from @RequestParam (ScheduleController) and are used as column
        // identifiers — projectIdField flows into a WHERE clause, duration/dependency are
        // row-key lookups. A value quote-escape does NOT protect an identifier position, so
        // validate against the identifier whitelist before use (rejects boolean/expression
        // injection such as "1=1 OR pm_wbs_project_id").
        SqlSafetyUtils.validateIdentifier(projectIdField, "critical-path projectIdField");
        SqlSafetyUtils.validateIdentifier(durationField, "critical-path durationField");
        SqlSafetyUtils.validateIdentifier(dependencyField, "critical-path dependencyField");

        // 1. Fetch all WBS nodes for the project
        String whereClause = projectId != null && !projectId.isEmpty()
                ? projectIdField + " = '" + projectId.replace("'", "''") + "'"
                : null;
        List<Map<String, Object>> nodes = dynamicDataMapper.queryList(
                tableName, null, whereClause, null, null, null
        );

        if (nodes == null || nodes.isEmpty()) {
            return CriticalPathResult.empty();
        }

        // 2. Build node map and adjacency
        Map<String, TaskNode> nodeMap = new LinkedHashMap<>();
        for (Map<String, Object> row : nodes) {
            String id = String.valueOf(row.get("id"));
            int duration = parseDuration(row.get(durationField));
            List<String> deps = parseDependencies(row.get(dependencyField));
            nodeMap.put(id, new TaskNode(id, duration, deps));
        }

        // 3. Topological sort
        List<String> topoOrder;
        try {
            topoOrder = topologicalSort(nodeMap);
        } catch (IllegalStateException e) {
            log.warn("Cycle detected in WBS dependencies for project {}: {}", projectId, e.getMessage());
            return CriticalPathResult.error("Cycle detected in task dependencies");
        }

        // 4. Forward pass
        for (String id : topoOrder) {
            TaskNode node = nodeMap.get(id);
            if (node == null) continue;

            int maxPredEF = 0;
            for (String depId : node.dependencies) {
                TaskNode dep = nodeMap.get(depId);
                if (dep != null) {
                    maxPredEF = Math.max(maxPredEF, dep.ef);
                }
            }
            node.es = maxPredEF;
            node.ef = node.es + node.duration;
        }

        // Find project duration
        int projectDuration = nodeMap.values().stream()
                .mapToInt(n -> n.ef)
                .max()
                .orElse(0);

        // 5. Backward pass
        List<String> reverseOrder = new ArrayList<>(topoOrder);
        Collections.reverse(reverseOrder);

        // Initialize LF to project duration for nodes with no successors
        Set<String> hasSuccessor = new HashSet<>();
        for (TaskNode node : nodeMap.values()) {
            for (String depId : node.dependencies) {
                hasSuccessor.add(depId);
            }
        }
        for (TaskNode node : nodeMap.values()) {
            if (!hasSuccessor.contains(node.id)) {
                node.lf = projectDuration;
                node.ls = node.lf - node.duration;
            }
        }

        // Build successor map for backward pass
        Map<String, List<String>> successors = new HashMap<>();
        for (TaskNode node : nodeMap.values()) {
            for (String depId : node.dependencies) {
                successors.computeIfAbsent(depId, k -> new ArrayList<>()).add(node.id);
            }
        }

        for (String id : reverseOrder) {
            TaskNode node = nodeMap.get(id);
            if (node == null) continue;

            List<String> succs = successors.getOrDefault(id, Collections.emptyList());
            if (!succs.isEmpty()) {
                int minSuccLS = succs.stream()
                        .map(nodeMap::get)
                        .filter(Objects::nonNull)
                        .mapToInt(s -> s.ls)
                        .min()
                        .orElse(projectDuration);
                node.lf = minSuccLS;
                node.ls = node.lf - node.duration;
            }
        }

        // 6. Compute slack and identify critical path
        List<String> criticalPathIds = new ArrayList<>();
        Map<String, Map<String, Integer>> scheduleMap = new LinkedHashMap<>();

        for (TaskNode node : nodeMap.values()) {
            int slack = node.ls - node.es;
            if (slack == 0 && node.duration > 0) {
                criticalPathIds.add(node.id);
            }
            Map<String, Integer> schedule = new HashMap<>();
            schedule.put("es", node.es);
            schedule.put("ef", node.ef);
            schedule.put("ls", node.ls);
            schedule.put("lf", node.lf);
            schedule.put("slack", slack);
            schedule.put("duration", node.duration);
            scheduleMap.put(node.id, schedule);
        }

        return new CriticalPathResult(criticalPathIds, scheduleMap, projectDuration, null);
    }

    private int parseDuration(Object value) {
        if (value == null) return 1; // default 1 day
        try {
            return Math.max(1, ((Number) value).intValue());
        } catch (Exception e) {
            try {
                return Math.max(1, Integer.parseInt(value.toString()));
            } catch (NumberFormatException nfe) {
                return 1;
            }
        }
    }

    private List<String> parseDependencies(Object value) {
        if (value == null) return Collections.emptyList();
        String str = value.toString().trim();
        if (str.isEmpty()) return Collections.emptyList();
        return Arrays.stream(str.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private List<String> topologicalSort(Map<String, TaskNode> nodeMap) {
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : nodeMap.keySet()) {
            inDegree.put(id, 0);
        }
        for (TaskNode node : nodeMap.values()) {
            for (String depId : node.dependencies) {
                if (nodeMap.containsKey(depId)) {
                    // node depends on depId, so depId → node (depId is predecessor)
                    // inDegree of node increases
                }
            }
        }
        // Recount: for each node, count how many predecessors it has (within nodeMap)
        for (TaskNode node : nodeMap.values()) {
            int count = 0;
            for (String depId : node.dependencies) {
                if (nodeMap.containsKey(depId)) count++;
            }
            inDegree.put(node.id, count);
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        // Build successor map
        Map<String, List<String>> successors = new HashMap<>();
        for (TaskNode node : nodeMap.values()) {
            for (String depId : node.dependencies) {
                if (nodeMap.containsKey(depId)) {
                    successors.computeIfAbsent(depId, k -> new ArrayList<>()).add(node.id);
                }
            }
        }

        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            result.add(id);
            for (String succId : successors.getOrDefault(id, Collections.emptyList())) {
                int newDeg = inDegree.get(succId) - 1;
                inDegree.put(succId, newDeg);
                if (newDeg == 0) {
                    queue.add(succId);
                }
            }
        }

        if (result.size() != nodeMap.size()) {
            throw new IllegalStateException("Dependency cycle detected");
        }

        return result;
    }

    // Internal task node for CPM calculation
    private static class TaskNode {
        final String id;
        final int duration;
        final List<String> dependencies;
        int es; // Earliest Start
        int ef; // Earliest Finish
        int ls = Integer.MAX_VALUE; // Latest Start
        int lf = Integer.MAX_VALUE; // Latest Finish

        TaskNode(String id, int duration, List<String> dependencies) {
            this.id = id;
            this.duration = duration;
            this.dependencies = dependencies;
        }
    }

    /**
     * Result of CPM computation.
     */
    public record CriticalPathResult(
            List<String> criticalPathNodeIds,
            Map<String, Map<String, Integer>> scheduleMap,
            int totalDuration,
            String error
    ) {
        public static CriticalPathResult empty() {
            return new CriticalPathResult(Collections.emptyList(), Collections.emptyMap(), 0, null);
        }

        public static CriticalPathResult error(String message) {
            return new CriticalPathResult(Collections.emptyList(), Collections.emptyMap(), 0, message);
        }
    }
}
