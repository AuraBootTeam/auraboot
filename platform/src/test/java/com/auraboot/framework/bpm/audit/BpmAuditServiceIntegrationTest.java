package com.auraboot.framework.bpm.audit;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.BpmAuditRecordEntity;
import com.auraboot.framework.bpm.mapper.BpmAuditRecordMapper;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link BpmAuditService} focusing on the JSONB
 * {@code details} field round-trip through the database.
 *
 * <p>Regression guard: before the fix in {@link BpmAuditRecordMapper},
 * {@code @Select} queries ignored the {@code autoResultMap=true} binding on the
 * entity and returned {@code details=null} even when PostgreSQL stored a
 * non-empty JSONB payload. This test inserts a record with a populated
 * {@code details} map and asserts every key survives the full MyBatis
 * mapper round-trip for all three {@code @Select} methods.
 */
class BpmAuditServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private BpmAuditRecordMapper bpmAuditRecordMapper;

    @Test
    @DisplayName("findByProcessInstance deserializes JSONB details map completely")
    void findByProcessInstance_returnsPopulatedDetails() {
        String processInstanceId = "pi-" + UlidGenerator.generate();
        BpmAuditRecordEntity inserted = insertRecordWithDetails(
                processInstanceId,
                null,
                "wd_leave_approval",
                "process_start",
                Map.of(
                        "processDefinitionId", "wd_leave_approval",
                        "businessKey", "bk-audit-test-123",
                        "activityId", "gw_approver",
                        "numeric", 42
                )
        );

        List<BpmAuditRecordEntity> records = bpmAuditRecordMapper.findByProcessInstance(processInstanceId);

        assertThat(records).hasSize(1);
        BpmAuditRecordEntity loaded = records.get(0);
        assertThat(loaded.getId()).isEqualTo(inserted.getId());
        assertThat(loaded.getDetails())
                .as("details JSONB must be fully deserialized, not null")
                .isNotNull()
                .containsEntry("processDefinitionId", "wd_leave_approval")
                .containsEntry("businessKey", "bk-audit-test-123")
                .containsEntry("activityId", "gw_approver")
                .containsEntry("numeric", 42);
    }

    @Test
    @DisplayName("findByTaskId deserializes JSONB details map completely")
    void findByTaskId_returnsPopulatedDetails() {
        String taskId = "task-" + UlidGenerator.generate();
        String processInstanceId = "pi-" + UlidGenerator.generate();
        insertRecordWithDetails(
                processInstanceId,
                taskId,
                "wd_leave_approval",
                "task_complete",
                Map.of(
                        "comment", "approved",
                        "toUserId", "303464250251284480"
                )
        );

        List<BpmAuditRecordEntity> records = bpmAuditRecordMapper.findByTaskId(taskId);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).getDetails())
                .isNotNull()
                .containsEntry("comment", "approved")
                .containsEntry("toUserId", "303464250251284480");
    }

    @Test
    @DisplayName("findByProcessDefinitionKey deserializes JSONB details map completely")
    void findByProcessDefinitionKey_returnsPopulatedDetails() {
        String processDefinitionKey = "audit_test_" + UlidGenerator.generate();
        String processInstanceId = "pi-" + UlidGenerator.generate();
        insertRecordWithDetails(
                processInstanceId,
                null,
                processDefinitionKey,
                "activity_event",
                Map.of(
                        "eventType", "ACTIVITY_STARTED",
                        "activityId", "task_manager_review"
                )
        );

        List<BpmAuditRecordEntity> records = bpmAuditRecordMapper.findByProcessDefinitionKey(processDefinitionKey);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).getDetails())
                .isNotNull()
                .containsEntry("eventType", "ACTIVITY_STARTED")
                .containsEntry("activityId", "task_manager_review");
    }

    private BpmAuditRecordEntity insertRecordWithDetails(String processInstanceId,
                                                         String taskId,
                                                         String processDefinitionKey,
                                                         String operation,
                                                         Map<String, Object> details) {
        BpmAuditRecordEntity entity = BpmAuditRecordEntity.builder()
                .pid(UlidGenerator.generate())
                .tenantId(MetaContext.getCurrentTenantId())
                .userId("integration-test-user")
                .operation(operation)
                .processInstanceId(processInstanceId)
                .taskId(taskId)
                .processDefinitionKey(processDefinitionKey)
                .version(1)
                .details(details)
                .ipAddress("127.0.0.1")
                .result("success")
                .createdAt(Instant.now())
                .build();
        bpmAuditRecordMapper.insert(entity);
        assertThat(entity.getId()).as("insert must populate auto-generated id").isNotNull();
        return entity;
    }
}
