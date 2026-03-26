package com.auraboot.framework.bpm.service;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.framework.versioning.VersionableResource;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * BPMN Process implementation of VersionableResource (GAP-023).
 * Snapshots include BPMN XML content, form bindings, and metadata.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BpmnVersionableResource implements VersionableResource {

    private final BpmProcessDefinitionMapper bpmMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getResourceType() {
        return "bpmn";
    }

    @Override
    public JsonNode createSnapshot(String resourceId) {
        BpmProcessDefinition process = bpmMapper.selectOne(
                new QueryWrapper<BpmProcessDefinition>().eq("pid", resourceId));
        if (process == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "BPMN process not found: " + resourceId);
        }

        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("processKey", process.getProcessKey());
        snapshot.put("processName", process.getProcessName());
        snapshot.put("description", process.getDescription());
        snapshot.put("category", process.getCategory());
        snapshot.put("bpmnContent", process.getBpmnContent());
        snapshot.put("status", process.getStatus());
        snapshot.put("version", process.getVersion());
        if (process.getFormBindings() != null) {
            snapshot.set("formBindings", objectMapper.valueToTree(process.getFormBindings()));
        }
        if (process.getBusinessDataBindings() != null) {
            snapshot.set("businessDataBindings", objectMapper.valueToTree(process.getBusinessDataBindings()));
        }

        return snapshot;
    }

    @Override
    public void applySnapshot(String resourceId, JsonNode snapshot) {
        BpmProcessDefinition process = bpmMapper.selectOne(
                new QueryWrapper<BpmProcessDefinition>().eq("pid", resourceId));
        if (process == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "BPMN process not found: " + resourceId);
        }

        if (snapshot.has("processName")) process.setProcessName(snapshot.get("processName").asText());
        if (snapshot.has("description")) process.setDescription(snapshot.path("description").asText(null));
        if (snapshot.has("bpmnContent")) process.setBpmnContent(snapshot.get("bpmnContent").asText());
        if (snapshot.has("status")) process.setStatus(snapshot.get("status").asText());

        bpmMapper.updateById(process);
        log.info("Applied version snapshot to BPMN process: {}", resourceId);
    }
}
