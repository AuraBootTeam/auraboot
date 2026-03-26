package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.chain.ApprovalChainExecutor;
import com.auraboot.framework.bpm.chain.CommandChainResult;
import com.auraboot.framework.bpm.dto.ApprovalActionRequest;
import com.auraboot.framework.bpm.dto.ApprovalTaskDTO;
import com.auraboot.framework.bpm.dto.CcRequest;
import com.auraboot.framework.bpm.dto.ReassignRequest;
import com.auraboot.framework.bpm.entity.ApprovalTask;
import com.auraboot.framework.bpm.mapper.ApprovalTaskMapper;
import com.auraboot.framework.bpm.service.BpmNotifyService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for approval tasks in APPROVAL-mode command chains.
 */
@Slf4j
@RestController
@RequestMapping("/api/bpm/approval-tasks")
@RequiredArgsConstructor
@Tag(name = "Approval Tasks", description = "Approval task management for command chains")
public class ApprovalController {

    private final ApprovalChainExecutor approvalChainExecutor;
    private final ApprovalTaskMapper approvalTaskMapper;
    private final UserService userService;
    private final BpmNotifyService bpmNotifyService;

    @GetMapping("/my-pending")
    @Operation(summary = "List pending approval tasks for current user")
    public ApiResponse<List<ApprovalTaskDTO>> getMyPending(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        Long userId = MetaContext.getCurrentUserId();
        String userIdJson = "[" + userId + "]";
        List<ApprovalTask> tasks = approvalTaskMapper.findPendingByAssigneeUserId(userIdJson);

        // Manual pagination for @Select-based query
        int start = (pageNum - 1) * pageSize;
        int end = Math.min(start + pageSize, tasks.size());
        List<ApprovalTask> page = start < tasks.size() ? tasks.subList(start, end) : List.of();

        return ApiResponse.success(page.stream().map(this::toDTO).toList());
    }

    @GetMapping("/my-history")
    @Operation(summary = "List completed tasks where current user was approver")
    public ApiResponse<Page<ApprovalTaskDTO>> getMyHistory(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status) {
        Long userId = MetaContext.getCurrentUserId();

        QueryWrapper<ApprovalTask> qw = new QueryWrapper<ApprovalTask>()
                .eq("actual_approver_id", userId)
                .ne("status", "pending")
                .orderByDesc("completed_at");
        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            qw.eq("status", status);
        }

        Page<ApprovalTask> page = approvalTaskMapper.selectPage(
                new Page<>(pageNum, pageSize), qw);

        Page<ApprovalTaskDTO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(this::toDTO).toList());
        return ApiResponse.success(result);
    }

    @GetMapping("/{taskPid}")
    @Operation(summary = "Get approval task detail")
    public ApiResponse<ApprovalTaskDTO> getTaskDetail(@PathVariable String taskPid) {
        ApprovalTask task = approvalTaskMapper.selectOne(
                new QueryWrapper<ApprovalTask>().eq("pid", taskPid));
        if (task == null) {
            return ApiResponse.error("Approval task not found: " + taskPid);
        }
        return ApiResponse.success(toDetailDTO(task));
    }

    @GetMapping("/count")
    @Operation(summary = "Get pending task count for badge display")
    public ApiResponse<Map<String, Integer>> getPendingCount() {
        Long userId = MetaContext.getCurrentUserId();
        String userIdJson = "[" + userId + "]";
        int count = approvalTaskMapper.countPendingByUserId(userIdJson);
        return ApiResponse.success(Map.of("pending", count));
    }

    /**
     * Get all approval comments/history for a specific business record.
     * Used by the ApprovalCommentsBlock DSL component to display approval timeline.
     */
    @GetMapping("/comments/{businessKey}")
    @Operation(summary = "Get approval comments for a business record")
    public ApiResponse<List<ApprovalTaskDTO>> getCommentsByBusinessKey(
            @PathVariable String businessKey) {
        QueryWrapper<ApprovalTask> qw = new QueryWrapper<ApprovalTask>()
                .eq("business_key", businessKey)
                .ne("status", "pending")
                .orderByDesc("completed_at");

        List<ApprovalTask> tasks = approvalTaskMapper.selectList(qw);
        List<ApprovalTaskDTO> dtos = tasks.stream().map(this::toCommentDTO).toList();
        return ApiResponse.success(dtos);
    }

    /**
     * Get all approval tasks (including pending) for a specific business record.
     * Provides full approval trail for the record.
     */
    @GetMapping("/trail/{businessKey}")
    @Operation(summary = "Get full approval trail for a business record")
    public ApiResponse<List<ApprovalTaskDTO>> getApprovalTrail(
            @PathVariable String businessKey) {
        QueryWrapper<ApprovalTask> qw = new QueryWrapper<ApprovalTask>()
                .eq("business_key", businessKey)
                .orderByAsc("created_at");

        List<ApprovalTask> tasks = approvalTaskMapper.selectList(qw);
        List<ApprovalTaskDTO> dtos = tasks.stream().map(this::toCommentDTO).toList();
        return ApiResponse.success(dtos);
    }

    @PostMapping("/{taskPid}/approve")
    @Operation(summary = "Approve a pending task")
    public ApiResponse<CommandChainResult> approve(
            @PathVariable String taskPid,
            @RequestBody ApprovalActionRequest request) {
        Long userId = MetaContext.getCurrentUserId();
        try {
            CommandChainResult result = approvalChainExecutor.handleApproval(
                    taskPid, userId, "approved", request.getComment(), request.getFormData(),
                    request.getSignature(), request.getAttachments());
            return ApiResponse.success(result);
        } catch (IllegalStateException e) {
            return ApiResponse.error(e.getMessage());
        } catch (SecurityException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/{taskPid}/reject")
    @Operation(summary = "Reject a pending task")
    public ApiResponse<CommandChainResult> reject(
            @PathVariable String taskPid,
            @RequestBody ApprovalActionRequest request) {
        Long userId = MetaContext.getCurrentUserId();
        if (request.getComment() == null || request.getComment().isBlank()) {
            return ApiResponse.error("Comment is required for rejection");
        }
        try {
            CommandChainResult result = approvalChainExecutor.handleApproval(
                    taskPid, userId, "rejected", request.getComment(), null,
                    request.getSignature(), request.getAttachments());
            return ApiResponse.success(result);
        } catch (IllegalStateException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/{taskPid}/reassign")
    @Operation(summary = "Reassign a pending task to new users")
    public ApiResponse<Void> reassign(
            @PathVariable String taskPid,
            @RequestBody ReassignRequest request) {
        Long userId = MetaContext.getCurrentUserId();
        if (request.getAssigneeUserIds() == null || request.getAssigneeUserIds().isEmpty()) {
            return ApiResponse.error("assigneeUserIds is required");
        }
        try {
            approvalChainExecutor.reassignTask(taskPid, userId, request.getAssigneeUserIds());
            return ApiResponse.success(null);
        } catch (IllegalStateException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * Carbon copy (CC) an approval task to additional users.
     * Creates informational notification records for each CC recipient.
     * The task itself is not modified — CC is read-only informational.
     */
    @PostMapping("/{taskPid}/cc")
    @Operation(summary = "Carbon copy an approval task to additional users")
    public ApiResponse<Void> carbonCopy(
            @PathVariable String taskPid,
            @RequestBody CcRequest request) {
        Long senderId = MetaContext.getCurrentUserId();
        if (request.getCcUserIds() == null || request.getCcUserIds().isEmpty()) {
            return ApiResponse.error("ccUserIds is required");
        }

        ApprovalTask task = approvalTaskMapper.selectOne(
                new QueryWrapper<ApprovalTask>().eq("pid", taskPid));
        if (task == null) {
            return ApiResponse.error("Approval task not found: " + taskPid);
        }

        // Use task.getPid() as taskId reference and chainExecutionId as process instance context
        String content = request.getComment() != null ? request.getComment()
                : "Task CC: " + task.getTaskTitle();
        bpmNotifyService.sendCarbonCopy(
                task.getPid(), task.getChainExecutionId(),
                senderId, request.getCcUserIds(), content);

        log.info("CC sent for task={}, sender={}, recipients={}", taskPid, senderId, request.getCcUserIds());
        return ApiResponse.success(null);
    }

    // ==================== Mapping ====================

    private ApprovalTaskDTO toDTO(ApprovalTask task) {
        return ApprovalTaskDTO.builder()
                .pid(task.getPid())
                .taskTitle(task.getTaskTitle())
                .taskDescription(task.getTaskDescription())
                .priority(task.getPriority())
                .status(task.getStatus())
                .assigneeStrategy(task.getAssigneeStrategy())
                .assigneeUserIds(task.getAssigneeUserIds())
                .actualApproverId(task.getActualApproverId())
                .processKey(task.getProcessKey())
                .businessKey(task.getBusinessKey())
                .chainExecutionId(task.getChainExecutionId())
                .formRef(task.getFormRef())
                .approvalComment(task.getApprovalComment())
                .deadlineAt(task.getDeadlineAt())
                .completedAt(task.getCompletedAt())
                .createdAt(task.getCreatedAt())
                .build();
    }

    /**
     * Maps to DTO with approval comment details including approver name, signature, and attachments.
     */
    private ApprovalTaskDTO toCommentDTO(ApprovalTask task) {
        ApprovalTaskDTO dto = toDTO(task);
        dto.setSignature(task.getSignature());
        dto.setAttachments(task.getAttachments());
        // Resolve approver display name
        if (task.getActualApproverId() != null) {
            try {
                User user = userService.findByUserId(task.getActualApproverId());
                if (user != null) {
                    dto.setApproverName(user.getNickName() != null ? user.getNickName() : user.getEmail());
                }
            } catch (Exception e) {
                log.warn("Failed to resolve approver name for userId={}: {}", task.getActualApproverId(), e.getMessage());
            }
        }
        // Also resolve submitter name from createdBy
        if (task.getCreatedBy() != null && task.getActualApproverId() == null) {
            try {
                User user = userService.findByUserId(task.getCreatedBy());
                if (user != null) {
                    dto.setApproverName(user.getNickName() != null ? user.getNickName() : user.getEmail());
                }
            } catch (Exception e) {
                log.warn("Failed to resolve submitter name for userId={}: {}", task.getCreatedBy(), e.getMessage());
            }
        }
        return dto;
    }

    private ApprovalTaskDTO toDetailDTO(ApprovalTask task) {
        ApprovalTaskDTO dto = toDTO(task);
        dto.setFormSnapshot(task.getFormSnapshot());
        dto.setApprovalData(task.getApprovalData());
        dto.setSignature(task.getSignature());
        dto.setAttachments(task.getAttachments());
        return dto;
    }
}
