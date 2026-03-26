package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.StateGraphCreateRequest;
import com.auraboot.framework.meta.dto.StateNodeDTO;
import com.auraboot.framework.meta.dto.StateTransitionDTO;
import com.auraboot.framework.meta.entity.StateGraphDefinition;
import com.auraboot.framework.meta.mapper.StateGraphDefinitionMapper;
import com.auraboot.framework.meta.service.StateGraphService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * State Graph Service implementation.
 * Manages CRUD, publish, and visualization of state graph definitions.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StateGraphServiceImpl implements StateGraphService {

    private final StateGraphDefinitionMapper stateGraphMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public StateGraphDefinition create(StateGraphCreateRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();

        List<StateNodeDTO> nodes = request.getNodes();
        List<StateTransitionDTO> transitions = request.getTransitions();
        validateGraphIntegrity(nodes, transitions);

        StateGraphDefinition definition = new StateGraphDefinition();
        definition.setPid(UniqueIdGenerator.generate());
        definition.setTenantId(tenantId);
        definition.setCode(request.getCode());
        definition.setDisplayName(request.getDisplayName());
        definition.setDescription(request.getDescription());
        definition.setModelCode(request.getModelCode());
        definition.setStateField(StringUtils.hasText(request.getStateField()) ? request.getStateField() : "status");
        definition.setNodes(serializeJson(nodes));
        definition.setTransitions(serializeJson(transitions));
        definition.setVersion(1);
        definition.setIsCurrent(true);
        definition.setRowVersion(1);
        definition.setStatus(Status.DRAFT.getCode());
        definition.setExtension(new com.auraboot.framework.meta.entity.payload.ExtensionBean());
        definition.setDeletedFlag(false);
        definition.setCreatedAt(Instant.now());
        definition.setUpdatedAt(Instant.now());

        stateGraphMapper.insertIdempotent(definition);
        log.info("Created state graph definition: code={}, modelCode={}", request.getCode(), request.getModelCode());
        return definition;
    }

    @Override
    public StateGraphDefinition getByPid(String pid) {
        StateGraphDefinition definition = stateGraphMapper.findByPid(pid);
        if (definition == null) {
            throw new BusinessException(ResponseCode.BadParam, "State graph not found: " + pid);
        }
        return definition;
    }

    @Override
    public StateGraphDefinition getCurrentByCode(String code) {
        // tenant_id is automatically added by TenantLineInnerInterceptor
        StateGraphDefinition definition = stateGraphMapper.findCurrentByCode(code);
        if (definition == null) {
            throw new BusinessException(ResponseCode.BadParam, "State graph not found: " + code);
        }
        return definition;
    }

    @Override
    public List<StateGraphDefinition> listByModelCode(String modelCode) {
        // tenant_id is automatically added by TenantLineInnerInterceptor
        return stateGraphMapper.findByModelCode(modelCode);
    }

    @Override
    @Transactional
    public StateGraphDefinition update(String pid, StateGraphCreateRequest request) {
        StateGraphDefinition existing = getByPid(pid);

        if (!Status.DRAFT.getCode().equals(existing.getStatus())) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Only DRAFT state graphs can be updated. Current status: " + existing.getStatus());
        }

        List<StateNodeDTO> nodes = request.getNodes();
        List<StateTransitionDTO> transitions = request.getTransitions();
        validateGraphIntegrity(nodes, transitions);

        existing.setDisplayName(request.getDisplayName());
        existing.setDescription(request.getDescription());
        existing.setStateField(StringUtils.hasText(request.getStateField()) ? request.getStateField() : "status");
        existing.setNodes(serializeJson(nodes));
        existing.setTransitions(serializeJson(transitions));
        existing.setUpdatedAt(Instant.now());
        existing.setRowVersion(existing.getRowVersion() + 1);

        stateGraphMapper.updateById(existing);
        log.info("Updated state graph definition: pid={}", pid);
        return existing;
    }

    @Override
    @Transactional
    public void publish(String pid) {
        StateGraphDefinition definition = getByPid(pid);

        if (!Status.DRAFT.getCode().equals(definition.getStatus())) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Only DRAFT state graphs can be published. Current status: " + definition.getStatus());
        }

        // Mark old versions as not current (tenant_id is automatically added by TenantLineInnerInterceptor)
        stateGraphMapper.markAsNotCurrent(definition.getCode());

        // Publish current version
        stateGraphMapper.publishById(definition.getId(), Status.PUBLISHED.getCode());
        log.info("Published state graph: code={}, version={}", definition.getCode(), definition.getVersion());
    }

    @Override
    @Transactional
    public void delete(String pid) {
        StateGraphDefinition definition = getByPid(pid);
        stateGraphMapper.softDelete(pid);
        log.info("Deleted state graph: pid={}, code={}", pid, definition.getCode());
    }

    @Override
    public Map<String, Object> getGraphVisualization(String code) {
        // tenant_id is automatically added by TenantLineInnerInterceptor
        StateGraphDefinition definition = stateGraphMapper.findCurrentByCode(code);
        if (definition == null) {
            throw new BusinessException(ResponseCode.BadParam, "State graph not found: " + code);
        }

        List<StateNodeDTO> nodes = parseNodes(definition.getNodes());
        List<StateTransitionDTO> transitions = parseTransitions(definition.getTransitions());

        // Build visualization structure
        List<Map<String, Object>> vizNodes = nodes.stream()
                .map(n -> {
                    Map<String, Object> node = new HashMap<>();
                    node.put("id", n.getCode());
                    node.put("label", n.getDisplayName() != null ? n.getDisplayName() : n.getCode());
                    node.put("type", n.getType());
                    node.put("description", n.getDescription());
                    if (n.getMetadata() != null) {
                        node.put("metadata", n.getMetadata());
                    }
                    return node;
                })
                .collect(Collectors.toList());

        List<Map<String, Object>> vizEdges = transitions.stream()
                .map(t -> {
                    Map<String, Object> edge = new HashMap<>();
                    edge.put("source", t.getFrom());
                    edge.put("target", t.getTo());
                    edge.put("label", t.getDisplayName() != null ? t.getDisplayName() : t.getTriggerCommand());
                    edge.put("triggerCommand", t.getTriggerCommand());
                    edge.put("guard", t.getGuard());
                    return edge;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("nodes", vizNodes);
        result.put("edges", vizEdges);
        result.put("code", definition.getCode());
        result.put("modelCode", definition.getModelCode());
        result.put("stateField", definition.getStateField());
        return result;
    }

    @Override
    public List<StateTransitionDTO> getTransitionsFromState(String code, String currentState) {
        // tenant_id is automatically added by TenantLineInnerInterceptor
        StateGraphDefinition definition = stateGraphMapper.findCurrentByCode(code);
        if (definition == null) {
            return Collections.emptyList();
        }

        List<StateTransitionDTO> transitions = parseTransitions(definition.getTransitions());
        return transitions.stream()
                .filter(t -> currentState.equals(t.getFrom()))
                .collect(Collectors.toList());
    }

    // ==================== Private Helpers ====================

    private void validateGraphIntegrity(List<StateNodeDTO> nodes, List<StateTransitionDTO> transitions) {
        if (nodes == null || nodes.isEmpty()) {
            throw new BusinessException(ResponseCode.BadParam, "State graph must have at least one node");
        }

        // Check exactly one INITIAL node
        long initialCount = nodes.stream()
                .filter(n -> "initial".equals(n.getType()))
                .count();
        if (initialCount != 1) {
            throw new BusinessException(ResponseCode.BadParam,
                    "State graph must have exactly one INITIAL node, found: " + initialCount);
        }

        // Check at least one TERMINAL node
        long terminalCount = nodes.stream()
                .filter(n -> "terminal".equals(n.getType()))
                .count();
        if (terminalCount < 1) {
            throw new BusinessException(ResponseCode.BadParam,
                    "State graph must have at least one TERMINAL node");
        }

        // Collect valid node codes
        Set<String> nodeCodes = nodes.stream()
                .map(StateNodeDTO::getCode)
                .collect(Collectors.toSet());

        // Validate transitions reference valid nodes
        if (transitions != null) {
            for (StateTransitionDTO t : transitions) {
                if (!nodeCodes.contains(t.getFrom())) {
                    throw new BusinessException(ResponseCode.BadParam,
                            "Transition references unknown source node: " + t.getFrom());
                }
                if (!nodeCodes.contains(t.getTo())) {
                    throw new BusinessException(ResponseCode.BadParam,
                            "Transition references unknown target node: " + t.getTo());
                }
            }
        }

        // Check no orphan nodes (except TERMINAL nodes which can have no outgoing edges)
        if (transitions != null && !transitions.isEmpty()) {
            Set<String> nodesWithOutgoing = transitions.stream()
                    .map(StateTransitionDTO::getFrom)
                    .collect(Collectors.toSet());

            for (StateNodeDTO node : nodes) {
                if (!"terminal".equals(node.getType()) && !nodesWithOutgoing.contains(node.getCode())) {
                    throw new BusinessException(ResponseCode.BadParam,
                            "Non-terminal node has no outgoing transitions: " + node.getCode());
                }
            }
        }
    }

    private String serializeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ResponseCode.BadParam, "Failed to serialize state graph data: " + e.getMessage());
        }
    }

    private List<StateNodeDTO> parseNodes(String nodesJson) {
        if (!StringUtils.hasText(nodesJson)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(nodesJson, new TypeReference<List<StateNodeDTO>>() {});
        } catch (Exception e) {
            log.error("Failed to parse state nodes JSON: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<StateTransitionDTO> parseTransitions(String transitionsJson) {
        if (!StringUtils.hasText(transitionsJson)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(transitionsJson, new TypeReference<List<StateTransitionDTO>>() {});
        } catch (Exception e) {
            log.error("Failed to parse state transitions JSON: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
