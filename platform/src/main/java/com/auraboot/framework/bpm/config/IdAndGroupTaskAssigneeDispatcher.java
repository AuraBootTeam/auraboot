package com.auraboot.framework.bpm.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.auraboot.smart.framework.engine.configuration.TaskAssigneeDispatcher;
import com.auraboot.smart.framework.engine.constant.AssigneeTypeConstant;
import com.auraboot.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.model.assembly.Activity;
import com.auraboot.smart.framework.engine.model.assembly.impl.AbstractActivity;
import com.auraboot.smart.framework.engine.model.instance.TaskAssigneeCandidateInstance;
import com.auraboot.framework.bpm.service.AssigneeResolverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Production task assignee dispatcher.
 * Reads assignee configuration from BPMN extension attributes (smart:assigneeType, smart:assigneeId)
 * written by JsonToBpmnConverter, and delegates resolution to AssigneeResolverService.
 *
 * Falls back to process starter if no assignee configuration or resolution fails.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdAndGroupTaskAssigneeDispatcher implements TaskAssigneeDispatcher {

    private final AssigneeResolverService assigneeResolverService;

    @Override
    public List<TaskAssigneeCandidateInstance> getTaskAssigneeCandidateInstance(
            Activity activity, ExecutionContext context) {
        List<TaskAssigneeCandidateInstance> candidates = new ArrayList<>();
        Map<String, Object> request = context != null ? context.getRequest() : Map.of();

        // 1. Read assignee config from BPMN extension attributes
        Map<String, String> properties = getActivityProperties(activity);
        String assigneeType = properties.getOrDefault("assigneeType", "");
        String assigneeId = properties.getOrDefault("assigneeId", "");
        String assigneeExpression = properties.getOrDefault("assignee", ""); // expression-based

        if (!assigneeType.isBlank()) {
            try {
                // Map BPMN assignee type to AssigneeResolverService rule type
                String ruleType = mapToRuleType(assigneeType);
                Map<String, Object> ruleConfig = buildRuleConfig(assigneeType, assigneeId, assigneeExpression);

                // Build context for resolver (includes process variables)
                Map<String, Object> resolverContext = new HashMap<>(request);

                List<String> resolvedIds = assigneeResolverService.resolve(ruleType, ruleConfig, resolverContext);
                for (int i = 0; i < resolvedIds.size(); i++) {
                    addCandidate(candidates, resolvedIds.get(i), AssigneeTypeConstant.USER, i + 1);
                }

                if (!candidates.isEmpty()) {
                    log.debug("Assignees resolved for activity {}: type={}, count={}",
                            activity.getId(), assigneeType, candidates.size());
                    return candidates;
                }
            } catch (Exception e) {
                log.warn("Failed to resolve assignees for activity {}: type={}, error={}",
                        activity.getId(), assigneeType, e.getMessage());
            }
        }

        // 2. Fallback: assign to process starter
        String startUserId = getStartUserId(context);
        if (startUserId != null && !startUserId.isBlank()) {
            addCandidate(candidates, startUserId, AssigneeTypeConstant.USER, 1);
            log.debug("Fallback: assigned activity {} to process starter {}", activity.getId(), startUserId);
        }

        return candidates;
    }

    private Map<String, String> getActivityProperties(Activity activity) {
        if (activity instanceof AbstractActivity abstractActivity) {
            Map<String, String> props = abstractActivity.getProperties();
            if (props != null) {
                return props;
            }
        }
        return Map.of();
    }

    /**
     * Map BPMN assignee type (from JsonToBpmnConverter) to AssigneeResolverService rule type.
     */
    private String mapToRuleType(String bpmnAssigneeType) {
        return switch (bpmnAssigneeType.toLowerCase()) {
            case "user" -> "specific_user";
            case "role" -> "role";
            case "dept" -> "department";
            case "starter" -> "starter";
            case "expression" -> "expression";
            default -> "starter";
        };
    }

    /**
     * Build rule config from BPMN attributes for AssigneeResolverService.
     */
    private Map<String, Object> buildRuleConfig(String type, String id, String expression) {
        Map<String, Object> config = new HashMap<>();

        if (!expression.isBlank()) {
            config.put("expression", expression);
        }

        if (!id.isBlank()) {
            // id may be comma-separated (e.g., "userId1,userId2" or "roleId1,roleId2")
            List<String> ids = List.of(id.split(","));
            switch (type.toLowerCase()) {
                case "user" -> config.put("userIds", ids);
                case "role" -> config.put("roleIds", ids);
                case "dept" -> config.put("deptIds", ids);
            }
        }

        return config;
    }

    private String getStartUserId(ExecutionContext context) {
        if (context == null) return null;
        Map<String, Object> request = context.getRequest();
        if (request == null) return null;
        Object userId = request.get(RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID);
        return userId != null ? userId.toString() : null;
    }

    private void addCandidate(List<TaskAssigneeCandidateInstance> list,
                              String id, String type, int priority) {
        TaskAssigneeCandidateInstance candidate = new TaskAssigneeCandidateInstance();
        candidate.setAssigneeId(id);
        candidate.setAssigneeType(type);
        candidate.setPriority(priority);
        list.add(candidate);
    }
}
