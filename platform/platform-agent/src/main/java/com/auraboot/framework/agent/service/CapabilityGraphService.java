package com.auraboot.framework.agent.service;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the capability composability graph (Task 1), extracted from
 * {@link CapabilityViewService}. Used both by the legacy on-the-fly read path
 * and by {@link CapabilitySyncService} when materializing {@code composable_with}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CapabilityGraphService {

    private final DynamicDataMapper dynamicDataMapper;
    private final CapabilityMappingSupport mappingSupport;

    // ==================== Task 1: Capability Graph ====================

    /**
     * Build a capability graph mapping each capability code to its composable (related) capability codes.
     * Edges are derived from 3 sources:
     * 1. SideEffect edges: Command A's sideEffects target model → Commands on that model are composable
     * 2. Automation edges: Automation on model triggers EXECUTE_COMMAND → those commands are composable
     * 3. State machine sequence: Command with toState matching another command's fromStates → sequential composability
     */
    public Map<String, List<String>> buildCapabilityGraph(Long tenantId) {
        Map<String, Set<String>> graph = new HashMap<>();

        // Load all published commands with their execution configs
        String cmdSql = "SELECT code, model_code, execution_config " +
                "FROM ab_command_definition WHERE tenant_id = #{params.tenantId} " +
                "AND status = 'published' AND is_current = true " +
                "AND deleted_flag = FALSE";
        List<Map<String, Object>> commands = dynamicDataMapper.selectByQuery(cmdSql,
                Map.of("tenantId", tenantId));

        // Index: model_code → list of command codes
        Map<String, List<String>> modelToCommands = new HashMap<>();
        // Index: (model_code, fromState) → list of command codes
        Map<String, List<String>> stateToCommands = new HashMap<>();

        for (Map<String, Object> cmd : commands) {
            String code = (String) cmd.get("code");
            String modelCode = (String) cmd.get("model_code");
            modelToCommands.computeIfAbsent(modelCode, k -> new ArrayList<>()).add(code);

            Map<String, Object> execConfig = mappingSupport.parseJson(mappingSupport.stringifyValue(cmd.get("execution_config")));
            if (execConfig != null && execConfig.get("fromStates") instanceof List<?> fromStates) {
                for (Object fs : fromStates) {
                    String key = modelCode + ":" + fs;
                    stateToCommands.computeIfAbsent(key, k -> new ArrayList<>()).add(code);
                }
            }
        }

        // Source 1: SideEffect edges
        for (Map<String, Object> cmd : commands) {
            String code = (String) cmd.get("code");
            Map<String, Object> execConfig = mappingSupport.parseJson(mappingSupport.stringifyValue(cmd.get("execution_config")));
            if (execConfig == null) continue;

            if (execConfig.get("sideEffects") instanceof List<?> sideEffects) {
                for (Object se : sideEffects) {
                    if (se instanceof Map<?, ?> seMap) {
                        Object actions = seMap.get("actions");
                        if (actions instanceof List<?> actionList) {
                            for (Object a : actionList) {
                                if (a instanceof Map<?, ?> am) {
                                    String targetModel = am.get("modelCode") != null
                                            ? (String) am.get("modelCode")
                                            : (String) am.get("targetModel");
                                    if (targetModel != null) {
                                        List<String> targetCmds = modelToCommands.getOrDefault(targetModel, List.of());
                                        graph.computeIfAbsent(code, k -> new HashSet<>()).addAll(targetCmds);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Source 2: Automation edges
        String autoSql = "SELECT model_code, actions " +
                "FROM ab_automation WHERE tenant_id = #{params.tenantId} " +
                "AND enabled = true AND deleted_flag = FALSE";
        List<Map<String, Object>> automations = dynamicDataMapper.selectByQuery(autoSql,
                Map.of("tenantId", tenantId));

        for (Map<String, Object> auto : automations) {
            String modelCode = (String) auto.get("model_code");
            List<String> sourceCmds = modelToCommands.getOrDefault(modelCode, List.of());
            List<Map<String, Object>> actions = mappingSupport.parseJsonList(mappingSupport.stringifyValue(auto.get("actions")));

            for (Map<String, Object> action : actions) {
                if ("execute_command".equals(action.get("type"))) {
                    String targetCmd = (String) action.get("commandCode");
                    if (targetCmd != null) {
                        // All commands on the automation's model are composable with the target command
                        for (String srcCmd : sourceCmds) {
                            graph.computeIfAbsent(srcCmd, k -> new HashSet<>()).add(targetCmd);
                        }
                    }
                }
            }
        }

        // Source 3: State machine sequence
        for (Map<String, Object> cmd : commands) {
            String code = (String) cmd.get("code");
            String modelCode = (String) cmd.get("model_code");
            Map<String, Object> execConfig = mappingSupport.parseJson(mappingSupport.stringifyValue(cmd.get("execution_config")));
            if (execConfig == null) continue;

            Object toState = execConfig.get("toState");
            if (toState != null) {
                String key = modelCode + ":" + toState;
                List<String> nextCommands = stateToCommands.getOrDefault(key, List.of());
                for (String nextCmd : nextCommands) {
                    if (!nextCmd.equals(code)) {
                        graph.computeIfAbsent(code, k -> new HashSet<>()).add(nextCmd);
                    }
                }
            }
        }

        // Convert Set to List
        Map<String, List<String>> result = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : graph.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return result;
    }
}
