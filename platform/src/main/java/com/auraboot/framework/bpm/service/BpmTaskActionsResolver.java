package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.dto.TaskActionDef;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves designer-authored {@code taskActions} entries declared on userTask
 * nodes in the process definition's {@code extension.designerJson}.
 *
 * <p>Introduced for Bug #8 Part 2 to let {@link TaskService#approveTask} /
 * {@link TaskService#rejectTask} inject a fallback {@code resultVariable /
 * resultValue} pair into the SmartEngine {@code complete} call's variables
 * when the caller did not supply one. This unblocks exclusiveGateway MVEL
 * conditions (e.g. {@code ${taskResult == 'approved'}}) from any non-frontend
 * code path (legacy clients, ApprovalChain dispatch, external API consumers).
 *
 * <p>This helper is extracted from {@link BpmFormService#getTaskActionsForNode}
 * to avoid a TaskService ↔ BpmFormService circular dependency and to make the
 * resolution logic reusable.
 *
 * <p>Red line: no silent fallback. If the process definition row is missing,
 * or designerJson parsing fails, we throw so the caller sees the misconfig.
 * A legitimate "no taskActions for this node" returns {@code null} because
 * plugin authors can legitimately omit taskActions (see TA-43 golden test).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BpmTaskActionsResolver {

    private final ObjectMapper objectMapper;
    private final BpmProcessDefinitionMapper processDefinitionMapper;

    /**
     * Load all declared {@code taskActions} for a node.
     *
     * @return list of actions, or {@code null} when the process definition has
     *         no designerJson or the node has no taskActions (this is a
     *         legitimate "no-op" signal, not an error).
     * @throws IllegalStateException when the process definition exists but
     *         designerJson is present and cannot be parsed as JSON.
     */
    @SuppressWarnings("unchecked")
    public List<TaskActionDef> getTaskActionsForNode(String processKey, String nodeId) {
        if (processKey == null || nodeId == null) {
            return null;
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        BpmProcessDefinition definition = processDefinitionMapper.selectOne(
                new QueryWrapper<BpmProcessDefinition>()
                        .eq("tenant_id", tenantId)
                        .eq("process_key", processKey)
                        .eq("is_current", true)
                        .eq("deleted_flag", false)
        );
        if (definition == null || definition.getExtension() == null) {
            return null;
        }
        Object designerObj = definition.getExtension().get("designerJson");
        if (designerObj == null) {
            return null;
        }
        Map<String, Object> designer;
        if (designerObj instanceof Map<?, ?> m) {
            designer = (Map<String, Object>) m;
        } else if (designerObj instanceof String s && !s.isBlank()) {
            try {
                designer = objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                // Red line: no silent fallback. designerJson present but malformed
                // is a genuine misconfiguration — surface it to the caller.
                throw new IllegalStateException(
                        "Failed to parse designerJson for process '" + processKey + "'", e);
            }
        } else {
            return null;
        }
        Object nodesObj = designer.get("nodes");
        if (!(nodesObj instanceof List<?> nodes)) {
            return null;
        }
        for (Object nodeObj : nodes) {
            if (!(nodeObj instanceof Map<?, ?> nodeMap)) {
                continue;
            }
            Object id = nodeMap.get("id");
            if (!nodeId.equals(id)) {
                continue;
            }
            Object data = nodeMap.get("data");
            if (!(data instanceof Map<?, ?> dataMap)) {
                return null;
            }
            Object actionsObj = dataMap.get("taskActions");
            if (!(actionsObj instanceof List<?> actionsList) || actionsList.isEmpty()) {
                return null;
            }
            try {
                return objectMapper.convertValue(actionsList,
                        new TypeReference<List<TaskActionDef>>() {});
            } catch (Exception e) {
                // Red line: malformed taskActions is misconfiguration, not missing data.
                throw new IllegalStateException(
                        "Failed to parse taskActions for node '" + nodeId
                                + "' in process '" + processKey + "'", e);
            }
        }
        return null;
    }

    /**
     * Merge the {@code resultVariable → resultValue} pair declared by the
     * first {@code type=complete} {@link TaskActionDef} whose {@code key}
     * matches {@code actionKey} into {@code vars}, without overwriting keys
     * the caller already supplied.
     *
     * <p>No-op when:
     * <ul>
     *   <li>{@code processKey} or {@code nodeId} is null (task not persisted yet)</li>
     *   <li>the node has no declared taskActions (e.g. pure BPMN process)</li>
     *   <li>no matching {@code complete} action is found (e.g. custom key)</li>
     *   <li>the matching action has null {@code resultVariable}</li>
     *   <li>{@code vars} already contains {@code resultVariable}</li>
     * </ul>
     *
     * @param processKey  process definition key of the running instance
     * @param nodeId      BPMN activity id of the task being completed
     * @param actionKey   {@code approve} / {@code reject} / custom action key
     * @param vars        variables map to mutate in-place
     */
    public void mergeActionResultVariable(String processKey, String nodeId,
                                          String actionKey, Map<String, Object> vars) {
        if (vars == null || actionKey == null) {
            return;
        }
        List<TaskActionDef> actions = getTaskActionsForNode(processKey, nodeId);
        if (actions == null || actions.isEmpty()) {
            return;
        }
        for (TaskActionDef action : actions) {
            if (!actionKey.equals(action.getKey())) {
                continue;
            }
            if (!"complete".equals(action.getType())) {
                continue;
            }
            String resultVariable = action.getResultVariable();
            if (resultVariable == null || resultVariable.isBlank()) {
                return;
            }
            // Caller-provided value wins. Use putIfAbsent so an explicitly-set
            // taskResult from the frontend (Bug #8 Part 1) is preserved.
            Object previous = vars.putIfAbsent(resultVariable, action.getResultValue());
            if (previous == null) {
                log.debug("Injected taskActions result: processKey={}, nodeId={}, actionKey={}, {}={}",
                        processKey, nodeId, actionKey, resultVariable, action.getResultValue());
            }
            return;
        }
    }

    /**
     * Utility: expose the resolver as a plain map shortcut for tests or ad-hoc
     * callers that want {@code {resultVariable: resultValue}} without mutating
     * an existing map.
     */
    public Map<String, Object> resolveActionResultAsMap(String processKey, String nodeId,
                                                        String actionKey) {
        Map<String, Object> out = new HashMap<>();
        mergeActionResultVariable(processKey, nodeId, actionKey, out);
        return out;
    }
}
