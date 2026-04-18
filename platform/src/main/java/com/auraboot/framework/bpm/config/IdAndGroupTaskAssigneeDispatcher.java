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

        // GAP-249: multi-instance expansion. When the userTask carries
        // smart:miCollection (emitted by JsonToBpmnConverter when
        // config.multiInstance.enabled=true), resolve the collection from the
        // process request context and emit one candidate per element. SmartEngine's
        // UserTaskBehavior.enter iterates the returned list 1:1 into EI/TI rows, so
        // this is the hook that makes parallel "会签" actually spawn N parallel
        // tasks. Each element is bound to the element variable (default:
        // currentApprover) when it is itself a user id / assignee reference.
        String miCollectionExpr = properties.getOrDefault("miCollection", "");
        if (!miCollectionExpr.isBlank()) {
            // GAP-263: when miCollection is explicitly declared, this userTask is a
            // multi-instance activity. The candidate list defines the iteration set,
            // so an empty collection MUST return an empty candidate list — NOT fall
            // through to assigneeType / starter fallback (which would silently
            // materialize a task against the wrong user). Per BPMN 2.0 §13.2 an
            // empty MI activity should be skipped entirely; the SmartEngine fork
            // currently does NOT honor this (see BLOCKED-UPSTREAM note on GAP-263),
            // but that's no excuse to mask the empty case here.
            List<String> miAssignees = resolveMultiInstanceAssignees(miCollectionExpr, request);
            for (int i = 0; i < miAssignees.size(); i++) {
                addCandidate(candidates, miAssignees.get(i), AssigneeTypeConstant.USER, i + 1);
            }
            if (candidates.isEmpty()) {
                log.warn("Multi-instance collection '{}' resolved to empty list for activity {}; "
                        + "returning empty candidate list. SmartEngine fork must short-circuit "
                        + "the MI activity (GAP-263 SEQ-MI-GAP-2).",
                        miCollectionExpr, activity.getId());
            } else {
                log.debug("Multi-instance expansion for activity {}: {} instances",
                        activity.getId(), candidates.size());
            }
            return candidates;
        }

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

    /**
     * Resolve a multi-instance collection expression to a list of assignee ids.
     *
     * <p>Accepts two forms:
     * <ul>
     *   <li>{@code ${varName}} — direct lookup of a process variable whose value is a
     *       {@code Collection} (List, Set, array) or comma-separated String.
     *   <li>bare variable name like {@code approverList} — same lookup without
     *       the template wrapper.
     * </ul>
     *
     * <p>We deliberately avoid SpEL here: the test contract and BPMN standard use
     * {@code ${expr}} placeholders, and the collection value is already the full
     * list — there is no reason to evaluate it as an expression. If richer
     * expressions are needed later, wire {@code ExpressionUtil} through the
     * {@code processEngineConfiguration}.
     */
    @SuppressWarnings("unchecked")
    private List<String> resolveMultiInstanceAssignees(String expr, Map<String, Object> context) {
        if (expr == null || expr.isBlank()) return List.of();
        String varName = expr.trim();
        if (varName.startsWith("${") && varName.endsWith("}")) {
            varName = varName.substring(2, varName.length() - 1).trim();
        }
        Object value = context != null ? context.get(varName) : null;
        if (value == null) {
            return List.of();
        }
        if (value instanceof java.util.Collection<?> coll) {
            return coll.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(Object::toString)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        if (value.getClass().isArray()) {
            Object[] arr = (Object[]) value;
            return java.util.Arrays.stream(arr)
                    .filter(java.util.Objects::nonNull)
                    .map(Object::toString)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        String str = value.toString();
        if (str.isBlank()) return List.of();
        return java.util.Arrays.stream(str.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
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
