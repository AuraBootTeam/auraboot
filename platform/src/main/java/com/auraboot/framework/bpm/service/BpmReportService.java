package com.auraboot.framework.bpm.service;

import com.auraboot.framework.bpm.entity.BpmAuditRecordEntity;
import com.auraboot.framework.bpm.mapper.BpmAuditRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * BPM report service.
 * Generates approval chain and audit trail reports for process instances.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BpmReportService {

    private final BpmAuditRecordMapper auditRecordMapper;

    /**
     * Generate approval chain report for a process instance.
     * Returns a chronologically ordered chain of all audit events.
     */
    public Map<String, Object> generateApprovalChainReport(String processInstanceId) {
        List<BpmAuditRecordEntity> auditRecords = auditRecordMapper.findByProcessInstance(processInstanceId);

        List<Map<String, Object>> chain = auditRecords.stream()
                .sorted(Comparator.comparing(r -> r.getCreatedAt() != null ? r.getCreatedAt() : Instant.MIN))
                .map(record -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", record.getPid());
                    entry.put("operation", record.getOperation());
                    entry.put("userId", record.getUserId());
                    entry.put("taskId", record.getTaskId());
                    entry.put("processDefinitionKey", record.getProcessDefinitionKey());
                    entry.put("result", record.getResult());
                    entry.put("details", record.getDetails());
                    entry.put("errorMessage", record.getErrorMessage());
                    entry.put("timestamp", record.getCreatedAt() != null ? record.getCreatedAt().toString() : null);
                    return entry;
                })
                .collect(Collectors.toList());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("processInstanceId", processInstanceId);
        report.put("totalSteps", chain.size());
        report.put("chain", chain);
        report.put("generatedAt", Instant.now().toString());

        return report;
    }
}
