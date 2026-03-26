package com.auraboot.framework.bpm.audit;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.BpmAuditRecordEntity;
import com.auraboot.framework.bpm.mapper.BpmAuditRecordMapper;
import com.auraboot.framework.common.util.UlidGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * BPM audit service.
 * Persists audit records to ab_bpm_audit_record and integrates with the
 * existing audit logging mechanism.
 *
 * @author AuraBoot Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BpmAuditService {

    private final BpmAuditRecordMapper bpmAuditRecordMapper;

    /**
     * Record process start audit log.
     */
    public void recordProcessStart(String processInstanceId, String processDefinitionId, String businessKey, String userId, String tenantId) {
        Map<String, Object> details = Map.of(
                "processDefinitionId", processDefinitionId,
                "businessKey", businessKey != null ? businessKey : ""
        );
        auditProcessOperation("process_start", processInstanceId, null, details);
    }

    /**
     * Record process suspend audit log.
     */
    public void recordProcessSuspend(String processInstanceId, String userId, String tenantId) {
        auditProcessOperation("process_suspend", processInstanceId, null, Map.of());
    }

    /**
     * Record process resume audit log.
     */
    public void recordProcessResume(String processInstanceId, String userId, String tenantId) {
        auditProcessOperation("process_resume", processInstanceId, null, Map.of());
    }

    /**
     * Record process terminate audit log.
     */
    public void recordProcessTerminate(String processInstanceId, String reason, String userId, String tenantId) {
        Map<String, Object> details = Map.of("reason", reason != null ? reason : "");
        auditProcessOperation("process_terminate", processInstanceId, null, details);
    }

    /**
     * Record task complete audit log.
     */
    public void recordTaskComplete(String taskId, String processInstanceId, String userId, String comment, String tenantId) {
        auditTaskOperation("task_complete", taskId, processInstanceId, userId, null, comment, null);
    }

    /**
     * Record task claim audit log.
     */
    public void recordTaskClaim(String taskId, String processInstanceId, String userId, String tenantId) {
        auditTaskOperation("task_claim", taskId, processInstanceId, null, userId, null, null);
    }

    /**
     * Record task delegate audit log.
     */
    public void recordTaskDelegate(String taskId, String processInstanceId, String fromUserId, String toUserId, String comment, String tenantId) {
        auditTaskOperation("task_delegate", taskId, processInstanceId, fromUserId, toUserId, comment, null);
    }

    /**
     * Record task transfer audit log.
     */
    public void recordTaskTransfer(String taskId, String processInstanceId, String fromUserId, String toUserId, String comment, String tenantId) {
        auditTaskOperation("task_transfer", taskId, processInstanceId, fromUserId, toUserId, comment, null);
    }

    /**
     * Record process event audit log.
     */
    public void recordProcessEvent(String processInstanceId, String eventType, String description, String userId, String tenantId) {
        Map<String, Object> details = Map.of(
                "eventType", eventType,
                "description", description != null ? description : ""
        );
        auditProcessOperation("process_event", processInstanceId, null, details);
    }

    /**
     * Record activity event audit log.
     */
    public void recordActivityEvent(String processInstanceId, String activityId, String eventType, String description, String userId, String tenantId) {
        Map<String, Object> details = Map.of(
                "activityId", activityId != null ? activityId : "",
                "eventType", eventType,
                "description", description != null ? description : ""
        );
        auditProcessOperation("activity_event", processInstanceId, null, details);
    }

    /**
     * Record task event audit log.
     */
    public void recordTaskEvent(String taskId, String processInstanceId, String eventType, String description, String userId, String tenantId) {
        Map<String, Object> details = Map.of(
                "eventType", eventType,
                "description", description != null ? description : ""
        );
        auditProcessOperation("task_event", processInstanceId, taskId, details);
    }

    /**
     * Record a process-level audit operation.
     *
     * @param operation operation type
     * @param processInstanceId process instance ID
     * @param taskId task ID (optional)
     * @param details operation details
     */
    public void auditProcessOperation(String operation, String processInstanceId, String taskId, Map<String, Object> details) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        String userId = getCurrentUserId();
        Instant timestamp = Instant.now();

        BpmAuditRecord auditRecord = BpmAuditRecord.builder()
                .tenantId(tenantId)
                .userId(userId)
                .operation(operation)
                .processInstanceId(processInstanceId)
                .taskId(taskId)
                .details(details)
                .timestamp(timestamp)
                .ipAddress(getCurrentUserIpAddress())
                .build();

        logAuditRecord(auditRecord);

        log.info("BPM audit recorded: operation={}, processInstanceId={}, taskId={}, userId={}, tenantId={}",
                operation, processInstanceId, taskId, userId, tenantId);
    }

    /**
     * Record a task-level audit operation.
     *
     * @param operation operation type (e.g. COMPLETE, TRANSFER, DELEGATE)
     * @param taskId task ID
     * @param processInstanceId process instance ID
     * @param fromUserId source user ID (for transfer/delegate)
     * @param toUserId target user ID (for transfer/delegate)
     * @param comment operation comment
     * @param variables task variables
     */
    public void auditTaskOperation(String operation, String taskId, String processInstanceId,
                                 String fromUserId, String toUserId, String comment, Map<String, Object> variables) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        String currentUserId = getCurrentUserId();
        Instant timestamp = Instant.now();

        Map<String, Object> details = Map.of(
                "fromUserId", fromUserId != null ? fromUserId : "",
                "toUserId", toUserId != null ? toUserId : "",
                "comment", comment != null ? comment : "",
                "variables", variables != null ? variables : Map.of()
        );

        BpmAuditRecord auditRecord = BpmAuditRecord.builder()
                .tenantId(tenantId)
                .userId(currentUserId)
                .operation(operation)
                .processInstanceId(processInstanceId)
                .taskId(taskId)
                .details(details)
                .timestamp(timestamp)
                .ipAddress(getCurrentUserIpAddress())
                .build();

        logAuditRecord(auditRecord);

        log.info("BPM task audit recorded: operation={}, taskId={}, processInstanceId={}, userId={}, tenantId={}",
                operation, taskId, processInstanceId, currentUserId, tenantId);
    }

    /**
     * Record a process definition audit operation.
     *
     * @param operation operation type (e.g. DEPLOY, UNDEPLOY, UPDATE)
     * @param processDefinitionKey process definition key
     * @param version version number
     * @param details operation details
     */
    public void auditProcessDefinitionOperation(String operation, String processDefinitionKey,
                                              Integer version, Map<String, Object> details) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        String userId = getCurrentUserId();
        Instant timestamp = Instant.now();

        BpmAuditRecord auditRecord = BpmAuditRecord.builder()
                .tenantId(tenantId)
                .userId(userId)
                .operation(operation)
                .processDefinitionKey(processDefinitionKey)
                .version(version)
                .details(details)
                .timestamp(timestamp)
                .ipAddress(getCurrentUserIpAddress())
                .build();

        logAuditRecord(auditRecord);

        log.info("BPM process definition audit recorded: operation={}, processDefinitionKey={}, version={}, userId={}, tenantId={}",
                operation, processDefinitionKey, version, userId, tenantId);
    }

    /**
     * Persist the audit record to database.
     * Converts from internal BpmAuditRecord to BpmAuditRecordEntity and inserts.
     *
     * @param auditRecord the audit record to persist
     */
    private void logAuditRecord(BpmAuditRecord auditRecord) {
        try {
            BpmAuditRecordEntity entity = BpmAuditRecordEntity.builder()
                    .pid(UlidGenerator.generate())
                    .tenantId(auditRecord.getTenantId() != null ? Long.valueOf(auditRecord.getTenantId()) : null)
                    .userId(auditRecord.getUserId())
                    .operation(auditRecord.getOperation())
                    .processInstanceId(auditRecord.getProcessInstanceId())
                    .taskId(auditRecord.getTaskId())
                    .processDefinitionKey(auditRecord.getProcessDefinitionKey())
                    .version(auditRecord.getVersion())
                    .details(auditRecord.getDetails())
                    .ipAddress(auditRecord.getIpAddress())
                    .result(auditRecord.getResult() != null ? auditRecord.getResult() : "success")
                    .errorMessage(auditRecord.getErrorMessage())
                    .createdAt(auditRecord.getTimestamp())
                    .build();

            bpmAuditRecordMapper.insert(entity);
            log.debug("BPM Audit Record persisted: pid={}, operation={}", entity.getPid(), entity.getOperation());
        } catch (Exception e) {
            // Do not let audit persistence failure break the main flow
            log.error("Failed to persist BPM audit record: {}", e.getMessage(), e);
        }
    }

    /**
     * Query audit records by process instance ID.
     *
     * @param processInstanceId the process instance ID
     * @return list of audit record entities ordered by created_at DESC
     */
    public List<BpmAuditRecordEntity> findByProcessInstance(String processInstanceId) {
        return bpmAuditRecordMapper.findByProcessInstance(processInstanceId);
    }

    /**
     * Query audit records by task ID.
     *
     * @param taskId the task ID
     * @return list of audit record entities ordered by created_at DESC
     */
    public List<BpmAuditRecordEntity> findByTaskId(String taskId) {
        return bpmAuditRecordMapper.findByTaskId(taskId);
    }

    private String getCurrentUserId() {
        return com.auraboot.framework.bpm.util.BpmSecurityUtil.getCurrentUserId();
    }

    /**
     * Get client IP address from the current HTTP request context.
     * Checks proxy headers (X-Forwarded-For, X-Real-IP, etc.) before falling back to remoteAddr.
     *
     * @return the client IP address, or "127.0.0.1" if not in a request context
     */
    private String getCurrentUserIpAddress() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            HttpServletRequest request = attributes.getRequest();
            return getClientIpAddress(request);
        } catch (Exception e) {
            log.debug("Failed to get IP address from RequestContext: {}", e.getMessage());
        }
        return "127.0.0.1";
    }

    /**
     * Extract real client IP address from the request, checking common proxy headers.
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String[] headerNames = {
            "X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP",
            "WL-Proxy-Client-IP", "http_client_ip", "http_x_forwarded_for"
        };

        for (String headerName : headerNames) {
            String ip = request.getHeader(headerName);
            if (StringUtils.hasText(ip) && !"unknown".equalsIgnoreCase(ip)) {
                if (ip.contains(",")) {
                    ip = ip.split(",")[0].trim();
                }
                return ip;
            }
        }

        return request.getRemoteAddr();
    }
}
