package com.auraboot.framework.bpm.chain;

import com.alibaba.smart.framework.engine.SmartEngine;
import com.alibaba.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import com.alibaba.smart.framework.engine.model.assembly.ProcessDefinition;
import com.alibaba.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.chain.CommandChainDefinition.ChainEdge;
import com.auraboot.framework.bpm.chain.CommandChainDefinition.ChainMode;
import com.auraboot.framework.bpm.chain.CommandChainDefinition.ChainNode;
import com.auraboot.framework.bpm.converter.JsonToBpmnConverter;
import com.auraboot.framework.common.util.UlidGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Command chain orchestration service.
 *
 * <p>Provides two execution modes:
 * <ul>
 *   <li><b>LOCAL_TX</b>: All command steps execute within a single Spring @Transactional.
 *       Any step failure rolls back the entire chain. SmartEngine runs in CUSTOM (memory) mode.</li>
 *   <li><b>SAGA</b>: Each step has its own transaction. SmartEngine persists process state.
 *       Failed steps can be retried, skipped, or compensated. (Phase 2 implementation)</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * CommandChainDefinition chain = loadChainDefinition("sales_shipment_chain");
 * Map<String, Object> payload = Map.of(
 *     "orderId", orderId,
 *     "warehouseId", warehouseId,
 *     "items", items,
 *     "totalAmount", totalAmount,
 *     "customerId", customerId
 * );
 *
 * CommandChainResult result = commandChainService.executeChain(chain, "ORDER-123", payload);
 * }</pre>
 *
 * @author AuraBoot Team
 * @since 3.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommandChainService {

    private final SmartEngine smartEngine;
    private final JsonToBpmnConverter jsonToBpmnConverter;
    private final ApprovalChainExecutor approvalChainExecutor;
    private final com.auraboot.framework.bpm.chain.saga.SagaExecutor sagaExecutor;

    /**
     * Execute a command chain. Dispatches to LOCAL_TX or SAGA based on chain mode.
     *
     * @param chain       the chain definition
     * @param businessKey unique business key for this chain execution (e.g., order ID)
     * @param payload     business data available to all chain steps as process variables
     * @return chain execution result
     */
    public CommandChainResult executeChain(CommandChainDefinition chain, String businessKey,
                                            Map<String, Object> payload) {
        if (chain.getChainMode() == ChainMode.LOCAL_TX) {
            return executeChainLocalTx(chain, businessKey, payload);
        } else if (chain.getChainMode() == ChainMode.APPROVAL) {
            return approvalChainExecutor.startChain(chain, businessKey, payload);
        } else {
            return executeChainSaga(chain, businessKey, payload);
        }
    }

    /**
     * LOCAL_TX mode: Execute all chain steps within a single Spring transaction.
     *
     * <p>SmartEngine runs in memory (CUSTOM mode). All command data changes happen in the
     * same JDBC transaction. If any step throws, the entire chain rolls back.</p>
     *
     * @param chain       the chain definition
     * @param businessKey unique business key
     * @param payload     business payload
     * @return chain execution result
     */
    @Transactional
    public CommandChainResult executeChainLocalTx(CommandChainDefinition chain, String businessKey,
                                                   Map<String, Object> payload) {
        String processKey = chain.getProcessKey();
        String chainExecutionId = UlidGenerator.generate();
        long chainStartTime = System.currentTimeMillis();

        log.info("Starting LOCAL_TX chain: processKey={}, businessKey={}, executionId={}",
                processKey, businessKey, chainExecutionId);

        // 1. Record original request for audit/replay
        Map<String, Object> processVars = new HashMap<>(payload);
        processVars.put("_chain_mode", "local_tx");
        processVars.put("_chain_execution_id", chainExecutionId);
        processVars.put("_chain_request_time", Instant.now().toString());
        processVars.put("_chain_business_key", businessKey);

        // 2. Build chain node configuration map and inject into process variables
        Map<String, Map<String, Object>> chainNodes = buildChainNodesConfig(chain);
        processVars.put("_chain_nodes", chainNodes);

        // 3. Inject tenant/user context
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        processVars.put(RequestMapSpecialKeyConstant.TENANT_ID, tenantId);
        processVars.put(RequestMapSpecialKeyConstant.PROCESS_BIZ_UNIQUE_ID, businessKey);

        Long userId = MetaContext.getCurrentUserId();
        if (userId != null) {
            processVars.put(RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID, String.valueOf(userId));
        }

        try {
            // 4. Deploy process definition if not already deployed
            ensureProcessDeployed(chain);

            // 5. Start process — SmartEngine auto-advances through all ServiceTasks
            //    since there are no UserTasks requiring manual intervention.
            //    Each ServiceTask calls CommandServiceTaskDelegate.execute() synchronously.
            String version = resolveLatestVersion(processKey);
            ProcessInstance instance = smartEngine.getProcessCommandService()
                    .start(processKey, version, processVars);

            long durationMs = System.currentTimeMillis() - chainStartTime;

            // 6. Collect step results from process variables
            Map<String, Object> stepResults = collectStepResults(processVars, chain);

            log.info("LOCAL_TX chain completed: processKey={}, businessKey={}, durationMs={}",
                    processKey, businessKey, durationMs);

            return CommandChainResult.builder()
                    .success(true)
                    .chainExecutionId(chainExecutionId)
                    .processInstanceId(instance.getInstanceId())
                    .processKey(processKey)
                    .businessKey(businessKey)
                    .chainMode(ChainMode.LOCAL_TX)
                    .stepResults(stepResults)
                    .durationMs(durationMs)
                    .build();

        } catch (CommandChainStepException e) {
            long durationMs = System.currentTimeMillis() - chainStartTime;
            log.error("LOCAL_TX chain FAILED at step [{}] command [{}]: {}",
                    e.getNodeId(), e.getCommandCode(), e.getMessage());

            // Transaction will be rolled back by @Transactional
            return CommandChainResult.builder()
                    .success(false)
                    .chainExecutionId(chainExecutionId)
                    .processKey(processKey)
                    .businessKey(businessKey)
                    .chainMode(ChainMode.LOCAL_TX)
                    .failedNodeId(e.getNodeId())
                    .failedCommandCode(e.getCommandCode())
                    .errorMessage(e.getMessage())
                    .durationMs(durationMs)
                    .build();
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - chainStartTime;
            log.error("LOCAL_TX chain FAILED: processKey={}, businessKey={}, error={}",
                    processKey, businessKey, e.getMessage(), e);

            return CommandChainResult.builder()
                    .success(false)
                    .chainExecutionId(chainExecutionId)
                    .processKey(processKey)
                    .businessKey(businessKey)
                    .chainMode(ChainMode.LOCAL_TX)
                    .errorMessage(e.getMessage())
                    .durationMs(durationMs)
                    .build();
        }
    }

    /**
     * SAGA mode: Each step has its own transaction with compensation support.
     * Process state is persisted to the database for recovery.
     *
     * <p>Current implementation provides a safe fallback to LOCAL_TX to avoid hard failure.
     * It preserves execution continuity while emitting explicit degradation markers.</p>
     */
    public CommandChainResult executeChainSaga(CommandChainDefinition chain, String businessKey,
                                                Map<String, Object> payload) {
        return sagaExecutor.execute(chain, businessKey, payload);
    }

    // ==================== Internal Methods ====================

    /**
     * Build the _chain_nodes config map from chain definition.
     * Key: nodeId, Value: node configuration (commandCode, operationType, params, etc.)
     */
    private Map<String, Map<String, Object>> buildChainNodesConfig(CommandChainDefinition chain) {
        Map<String, Map<String, Object>> chainNodes = new LinkedHashMap<>();

        for (ChainNode node : chain.getNodes()) {
            if (!"serviceTask".equals(node.getType())) {
                continue; // Only service tasks need config
            }
            if (node.getData() == null || !"command".equals(node.getData().getServiceType())) {
                continue;
            }

            Map<String, Object> config = new HashMap<>();
            config.put("commandCode", node.getData().getCommandCode());
            config.put("operationType", node.getData().getOperationType());
            config.put("params", node.getData().getParams());
            config.put("targetRecordId", node.getData().getTargetRecordId());
            config.put("onFail", node.getData().getOnFail() != null
                    ? node.getData().getOnFail().name() : "abort");
            config.put("condition", node.getData().getCondition());

            chainNodes.put(node.getId(), config);
        }

        return chainNodes;
    }

    /**
     * Ensure the BPMN process definition is deployed to SmartEngine.
     * Converts the chain definition to BPMN XML and deploys it.
     */
    private void ensureProcessDeployed(CommandChainDefinition chain) {
        String processKey = chain.getProcessKey();

        // Check if already deployed
        boolean alreadyDeployed = smartEngine.getRepositoryQueryService()
                .getAllCachedProcessDefinition()
                .stream()
                .anyMatch(pd -> processKey.equals(pd.getId()));

        if (alreadyDeployed) {
            return;
        }

        // Convert chain definition to BPMN-compatible JSON, then to XML
        Map<String, Object> processJson = chainToProcessJson(chain);
        String bpmnXml = jsonToBpmnConverter.convertFromMap(processJson);

        log.info("Deploying chain process: processKey={}", processKey);

        // Deploy BPMN XML to SmartEngine
        smartEngine.getRepositoryCommandService()
                .deployWithUTF8Content(bpmnXml);

        log.info("Chain process deployed: processKey={}", processKey);
    }

    /**
     * Convert CommandChainDefinition to the JSON format expected by JsonToBpmnConverter.
     */
    private Map<String, Object> chainToProcessJson(CommandChainDefinition chain) {
        Map<String, Object> processJson = new LinkedHashMap<>();
        processJson.put("key", chain.getProcessKey());
        processJson.put("name", chain.getName() != null ? chain.getName() : chain.getProcessKey());

        // Convert nodes
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (ChainNode node : chain.getNodes()) {
            Map<String, Object> nodeMap = new LinkedHashMap<>();
            nodeMap.put("id", node.getId());
            nodeMap.put("type", node.getType());

            Map<String, Object> data = new LinkedHashMap<>();
            if (node.getData() != null) {
                data.put("label", node.getData().getLabel());
                if ("command".equals(node.getData().getServiceType())) {
                    data.put("serviceType", "command");
                    data.put("commandCode", node.getData().getCommandCode());
                }
            }

            // For serviceTask with COMMAND type, config tells the converter to use commandServiceTaskDelegate
            if ("serviceTask".equals(node.getType()) && node.getData() != null
                    && "command".equals(node.getData().getServiceType())) {
                Map<String, Object> config = new LinkedHashMap<>();
                config.put("serviceType", "command");
                data.put("config", config);
            }

            nodeMap.put("data", data);
            nodes.add(nodeMap);
        }
        processJson.put("nodes", nodes);

        // Convert edges
        List<Map<String, Object>> edges = new ArrayList<>();
        if (chain.getEdges() != null) {
            for (ChainEdge edge : chain.getEdges()) {
                Map<String, Object> edgeMap = new LinkedHashMap<>();
                edgeMap.put("id", edge.getId());
                edgeMap.put("source", edge.getSource());
                edgeMap.put("target", edge.getTarget());

                Map<String, Object> edgeData = new LinkedHashMap<>();
                if (edge.getLabel() != null) {
                    edgeData.put("label", edge.getLabel());
                }
                if (edge.getCondition() != null) {
                    Map<String, Object> condition = new LinkedHashMap<>();
                    condition.put("type", edge.getCondition().getType());
                    condition.put("content", edge.getCondition().getContent());
                    edgeData.put("condition", condition);
                }
                edgeMap.put("data", edgeData);

                edges.add(edgeMap);
            }
        } else {
            // Auto-generate sequential edges if not provided
            edges = generateSequentialEdges(chain.getNodes());
        }
        processJson.put("edges", edges);

        return processJson;
    }

    /**
     * Generate sequential edges for a simple linear chain.
     */
    private List<Map<String, Object>> generateSequentialEdges(List<ChainNode> nodes) {
        List<Map<String, Object>> edges = new ArrayList<>();
        for (int i = 0; i < nodes.size() - 1; i++) {
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("id", "flow_" + i);
            edge.put("source", nodes.get(i).getId());
            edge.put("target", nodes.get(i + 1).getId());
            edge.put("data", Map.of());
            edges.add(edge);
        }
        return edges;
    }

    /**
     * Resolve the latest deployed version for a process key.
     */
    private String resolveLatestVersion(String processKey) {
        return smartEngine.getRepositoryQueryService()
                .getAllCachedProcessDefinition()
                .stream()
                .filter(pd -> processKey.equals(pd.getId()))
                .map(ProcessDefinition::getVersion)
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException(
                        "Process not deployed: " + processKey));
    }

    /**
     * Collect step results from process variables after chain execution.
     */
    private Map<String, Object> collectStepResults(Map<String, Object> processVars,
                                                    CommandChainDefinition chain) {
        Map<String, Object> results = new LinkedHashMap<>();

        for (ChainNode node : chain.getNodes()) {
            if (!"serviceTask".equals(node.getType())) {
                continue;
            }
            String nodeId = node.getId();
            Map<String, Object> stepResult = new LinkedHashMap<>();

            Object success = processVars.get("_step_" + nodeId + "_success");
            stepResult.put("success", Boolean.TRUE.equals(success));

            Object result = processVars.get("_step_" + nodeId + "_result");
            if (result != null) {
                stepResult.put("data", result);
            }

            Object recordId = processVars.get("_step_" + nodeId + "_recordId");
            if (recordId != null) {
                stepResult.put("recordId", recordId);
            }

            Object skipped = processVars.get("_step_" + nodeId + "_skipped");
            if (Boolean.TRUE.equals(skipped)) {
                stepResult.put("skipped", true);
            }

            Object error = processVars.get("_step_" + nodeId + "_error");
            if (error != null) {
                stepResult.put("error", error);
            }

            results.put(nodeId, stepResult);
        }

        return results;
    }
}
