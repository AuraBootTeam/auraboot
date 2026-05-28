package com.auraboot.framework.automation.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.dto.AutomationNodeExecutionDTO;
import com.auraboot.framework.automation.entity.AutomationNodeExecution;
import com.auraboot.framework.automation.mapper.AutomationNodeExecutionMapper;
import com.auraboot.framework.automation.service.AutomationExecutionQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationExecutionQueryServiceImpl implements AutomationExecutionQueryService {

    private final AutomationNodeExecutionMapper mapper;

    @Override
    public List<AutomationNodeExecutionDTO> getNodeStatusesByLogId(Long automationLogId) {
        if (automationLogId == null) {
            return Collections.emptyList();
        }
        Long tenantId = requireTenant();
        return mapper.findByLogIdAndTenant(tenantId, automationLogId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public List<AutomationNodeExecutionDTO> getNodeStatusesByProcessInstanceId(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            return Collections.emptyList();
        }
        Long tenantId = requireTenant();
        return mapper.findByProcessInstanceIdAndTenant(tenantId, processInstanceId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public AutomationNodeExecutionDTO toDto(AutomationNodeExecution row) {
        return AutomationNodeExecutionDTO.builder()
                .nodeId(row.getNodeId())
                .status(row.getStatus())
                .startedAt(row.getStartedAt())
                .completedAt(row.getCompletedAt())
                .errorMessage(row.getErrorMessage())
                .processInstanceId(row.getProcessInstanceId())
                .build();
    }

    private Long requireTenant() {
        if (!MetaContext.exists() || MetaContext.getCurrentTenantId() == null) {
            // Fail fast — no MetaContext means we cannot enforce row-level isolation.
            throw new IllegalStateException(
                    "AutomationExecutionQuery requires a tenant context (MetaContext)");
        }
        return MetaContext.getCurrentTenantId();
    }
}
