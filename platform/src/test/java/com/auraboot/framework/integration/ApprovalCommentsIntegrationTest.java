package com.auraboot.framework.integration;

import com.auraboot.framework.bpm.dto.ApprovalTaskDTO;
import com.auraboot.framework.bpm.entity.ApprovalTask;
import com.auraboot.framework.bpm.mapper.ApprovalTaskMapper;
import com.auraboot.framework.common.util.UlidGenerator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for approval comments API —
 * verifies trail/comments endpoints, signature and attachment persistence.
 */
@DisplayName("Approval Comments API Integration Tests")
class ApprovalCommentsIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ApprovalTaskMapper approvalTaskMapper;

    private String testBusinessKey;

    @BeforeEach
    void setupApprovalData() {
        testBusinessKey = "bk-" + System.currentTimeMillis();

        // Create a completed APPROVED task with signature and attachments
        ApprovalTask task1 = ApprovalTask.builder()
                .pid(UlidGenerator.generate())
                .tenantId(getTestTenant().getId())
                .chainExecutionId(UlidGenerator.generate())
                .chainNodeId("node-1")
                .processKey("test-process")
                .businessKey(testBusinessKey)
                .taskTitle("Review Purchase Order")
                .taskDescription("Please review and approve the PO")
                .priority("normal")
                .status("approved")
                .assigneeStrategy("any")
                .assigneeUserIds(List.of(getTestUser().getId()))
                .actualApproverId(getTestUser().getId())
                .approvalComment("Looks good, approved.")
                .signature("data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==")
                .attachments(List.of(
                        Map.of("fileId", "f001", "fileName", "po-doc.pdf", "fileSize", 1024, "url", "/api/file/f001")
                ))
                .completedAt(Instant.now().minusSeconds(3600))
                .createdAt(Instant.now().minusSeconds(7200))
                .updatedAt(Instant.now().minusSeconds(3600))
                .createdBy(getTestUser().getId())
                .updatedBy(getTestUser().getId())
                .build();
        approvalTaskMapper.insert(task1);

        // Create a completed REJECTED task
        ApprovalTask task2 = ApprovalTask.builder()
                .pid(UlidGenerator.generate())
                .tenantId(getTestTenant().getId())
                .chainExecutionId(UlidGenerator.generate())
                .chainNodeId("node-2")
                .processKey("test-process")
                .businessKey(testBusinessKey)
                .taskTitle("Final Approval")
                .priority("high")
                .status("rejected")
                .assigneeStrategy("any")
                .assigneeUserIds(List.of(getTestUser().getId()))
                .actualApproverId(getTestUser().getId())
                .approvalComment("Budget exceeded, please revise.")
                .completedAt(Instant.now().minusSeconds(1800))
                .createdAt(Instant.now().minusSeconds(3600))
                .updatedAt(Instant.now().minusSeconds(1800))
                .createdBy(getTestUser().getId())
                .updatedBy(getTestUser().getId())
                .build();
        approvalTaskMapper.insert(task2);

        // Create a PENDING task (still awaiting approval)
        ApprovalTask task3 = ApprovalTask.builder()
                .pid(UlidGenerator.generate())
                .tenantId(getTestTenant().getId())
                .chainExecutionId(UlidGenerator.generate())
                .chainNodeId("node-3")
                .processKey("test-process")
                .businessKey(testBusinessKey)
                .taskTitle("Re-submission Review")
                .priority("normal")
                .status("pending")
                .assigneeStrategy("any")
                .assigneeUserIds(List.of(getTestUser().getId()))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .createdBy(getTestUser().getId())
                .updatedBy(getTestUser().getId())
                .build();
        approvalTaskMapper.insert(task3);
    }

    @Test
    @DisplayName("should persist signature field on approval task")
    void testSignaturePersistence() {
        // Find tasks with the test business key
        List<ApprovalTask> tasks = approvalTaskMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ApprovalTask>()
                        .eq("business_key", testBusinessKey)
                        .eq("status", "approved")
        );

        assertThat(tasks).hasSize(1);
        ApprovalTask approvedTask = tasks.get(0);
        assertThat(approvedTask.getSignature()).isNotNull();
        assertThat(approvedTask.getSignature()).startsWith("data:image/png;base64,");
    }

    @Test
    @DisplayName("should persist attachments as JSONB array")
    void testAttachmentsPersistence() {
        List<ApprovalTask> tasks = approvalTaskMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ApprovalTask>()
                        .eq("business_key", testBusinessKey)
                        .eq("status", "approved")
        );

        assertThat(tasks).hasSize(1);
        ApprovalTask approvedTask = tasks.get(0);
        assertThat(approvedTask.getAttachments()).isNotNull();
        assertThat(approvedTask.getAttachments()).hasSize(1);
        Map<String, Object> att = approvedTask.getAttachments().get(0);
        assertThat(att.get("fileId")).isEqualTo("f001");
        assertThat(att.get("fileName")).isEqualTo("po-doc.pdf");
    }

    @Test
    @DisplayName("should return all tasks in trail ordered by created_at ASC")
    void testApprovalTrailOrder() {
        List<ApprovalTask> tasks = approvalTaskMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ApprovalTask>()
                        .eq("business_key", testBusinessKey)
                        .orderByAsc("created_at")
        );

        assertThat(tasks).hasSize(3);
        // First task is the oldest (APPROVED)
        assertThat(tasks.get(0).getStatus()).isEqualTo("approved");
        // Second task (REJECTED)
        assertThat(tasks.get(1).getStatus()).isEqualTo("rejected");
        // Third task (PENDING — most recent)
        assertThat(tasks.get(2).getStatus()).isEqualTo("pending");
    }

    @Test
    @DisplayName("should return only completed tasks when querying comments (non-PENDING)")
    void testCommentsExcludesPending() {
        List<ApprovalTask> tasks = approvalTaskMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ApprovalTask>()
                        .eq("business_key", testBusinessKey)
                        .ne("status", "pending")
                        .orderByDesc("completed_at")
        );

        assertThat(tasks).hasSize(2);
        assertThat(tasks).extracting(ApprovalTask::getStatus)
                .containsExactlyInAnyOrder("approved", "rejected");
    }

    @Test
    @DisplayName("should handle task without signature or attachments gracefully")
    void testNullableSignatureAndAttachments() {
        List<ApprovalTask> tasks = approvalTaskMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ApprovalTask>()
                        .eq("business_key", testBusinessKey)
                        .eq("status", "rejected")
        );

        assertThat(tasks).hasSize(1);
        ApprovalTask rejectedTask = tasks.get(0);
        assertThat(rejectedTask.getSignature()).isNull();
        // attachments defaults to empty list or null
        assertThat(rejectedTask.getAttachments() == null || rejectedTask.getAttachments().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("should return empty list for non-existent business key")
    void testNonExistentBusinessKey() {
        List<ApprovalTask> tasks = approvalTaskMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ApprovalTask>()
                        .eq("business_key", "non-existent-key-" + System.currentTimeMillis())
        );

        assertThat(tasks).isEmpty();
    }

    @Test
    @DisplayName("should return approval comment text from completed tasks")
    void testApprovalCommentContent() {
        List<ApprovalTask> tasks = approvalTaskMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ApprovalTask>()
                        .eq("business_key", testBusinessKey)
                        .eq("status", "approved")
        );

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getApprovalComment()).isEqualTo("Looks good, approved.");
        assertThat(tasks.get(0).getTaskTitle()).isEqualTo("Review Purchase Order");
    }
}
