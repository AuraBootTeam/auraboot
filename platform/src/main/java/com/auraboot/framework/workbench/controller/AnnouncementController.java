package com.auraboot.framework.workbench.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.workbench.dto.AnnouncementDTO;
import com.auraboot.framework.workbench.service.AnnouncementService;

import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Compatibility routes delegate all writes to the same authorized announcement commands. */
@RestController
@RequestMapping("/api/announcements")
@RequiredArgsConstructor
@Tag(name = "Announcements", description = "Tenant announcements")
@AuthenticatedAccess(
        "recipient listing is constrained to current tenant membership and sealed recipients")
public class AnnouncementController {
    private final AnnouncementService announcementService;
    private final com.auraboot.framework.workbench.service.AnnouncementCapabilityService capabilities;

    @GetMapping("/capabilities")
    @RequirePermission("announcement.announcement.publish")
    public ApiResponse<Map<String,Object>> capabilities() {return ApiResponse.success(capabilities.capabilities());}

    @GetMapping("/approval-readiness")
    @RequirePermission("announcement.announcement.submit")
    public ApiResponse<Map<String,Object>> approvalReadiness() {return ApiResponse.success(capabilities.approvalReadiness());}

    private final com.auraboot.framework.workbench.service.AnnouncementReleaseService releases;
    @GetMapping("/records/{pid}/publication")
    @RequirePermission("announcement.announcement.read")
    public ApiResponse<Map<String,Object>> publication(@PathVariable String pid) {return ApiResponse.success(releases.summary(pid));}
    private final com.auraboot.framework.workbench.service.AnnouncementOperatingPolicyService operatingPolicies;

    @GetMapping("/policy")
    @RequirePermission("announcement.announcement.policy_manage")
    public ApiResponse<com.auraboot.framework.workbench.service.AnnouncementTenantPolicyService.Policy> policy() {
        return ApiResponse.success(operatingPolicies.view());
    }
    @GetMapping("/policy/approval-options")
    @RequirePermission("announcement.announcement.policy_manage")
    public ApiResponse<List<com.auraboot.framework.workbench.service.AnnouncementAudienceService.Option>> policyDefinitions() {
        return ApiResponse.success(operatingPolicies.definitions());
    }
    private final CommandExecutor commandExecutor;
    private final com.auraboot.framework.workbench.service.AnnouncementApprovalService approvals;

    @GetMapping("/{pid}/approval")
    @RequirePermission("announcement.announcement.read")
    public ApiResponse<Map<String,Object>> approvalSummary(@PathVariable String pid) { return ApiResponse.success(approvals.summary(pid)); }
    private final com.auraboot.framework.workbench.service.AnnouncementAudienceService audiences;
    private final com.auraboot.framework.workbench.service.AnnouncementAccessService access;
    private final com.auraboot.framework.workbench.service.AnnouncementPolicyService policies;

    @GetMapping("/records/{pid}/management-summary")
    @RequirePermission("announcement.announcement.read")
    public ApiResponse<Map<String,Object>> managementSummary(@PathVariable String pid) {
        return ApiResponse.success(access.managementSummary(pid));
    }

    @GetMapping("/audience-options")
    @RequirePermission("announcement.announcement.publish")
    public ApiResponse<List<com.auraboot.framework.workbench.service.AnnouncementAudienceService.Option>> audienceOptions(
            @RequestParam String kind) {
        return ApiResponse.success(audiences.options(kind));
    }

    private final com.auraboot.framework.workbench.service.AnnouncementApprovalCatalog approvalCatalog;

    @GetMapping("/approval-options")
    @RequirePermission("announcement.announcement.publish")
    public ApiResponse<List<com.auraboot.framework.workbench.service.AnnouncementAudienceService.Option>> approvalOptions() {
        access.actor("publish");
        return ApiResponse.success(approvalCatalog.choices());
    }

    public record AudiencePreviewRequest(String recordPid,
            com.auraboot.framework.workbench.service.AnnouncementAudienceService.Rule audience) {}

    @PostMapping("/audience-preview")
    @RequirePermission("announcement.announcement.publish")
    public ApiResponse<com.auraboot.framework.workbench.service.AnnouncementAudienceService.Resolution> preview(
            @RequestBody AudiencePreviewRequest request) {
        access.manage(request.recordPid(), "publish", false);
        var rule = request.audience() == null ? policies.load(request.recordPid()).audience() : request.audience();
        return ApiResponse.success(audiences.resolve(rule));
    }


    @GetMapping
    public ApiResponse<List<AnnouncementDTO>> list(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(announcementService.listActive(limit));
    }

    @PostMapping
    @RequirePermission("announcement.announcement.edit")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> input) {
        return execute("announcement:create_announcement", null, input, "CREATE");
    }

    @PutMapping("/{pid}")
    @RequirePermission("announcement.announcement.edit")
    public ApiResponse<Map<String, Object>> update(
            @PathVariable String pid, @RequestBody Map<String, Object> input) {
        return execute("announcement:update_announcement", pid, input, "UPDATE");
    }

    @DeleteMapping("/{pid}")
    @RequirePermission("announcement.announcement.delete")
    public ApiResponse<Map<String, Object>> delete(
            @PathVariable String pid,
            @RequestHeader(value = "Idempotency-Key", required = false) String operationId,
            @RequestHeader(value = "If-Match", required = false) Integer revision) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("operationId", operationId);
        input.put("expectedRevision", revision);
        return execute("announcement:delete_announcement", pid, input, "DELETE");
    }

    private ApiResponse<Map<String, Object>> execute(
            String code, String pid, Map<String, Object> input, String operation) {
        Map<String, Object> payload = new LinkedHashMap<>(input);
        Object operationId = payload.remove("operationId");
        Object revision = payload.remove("expectedRevision");
        if (operationId != null && !(operationId instanceof String))
            throw BusinessException.i18n("announcement.error.invalid_input");
        if (revision != null
                && (!(revision instanceof Number n)
                        || n.longValue() < 1
                        || n.longValue() > Integer.MAX_VALUE
                        || n.doubleValue() != n.longValue()))
            throw BusinessException.i18n("announcement.error.invalid_input");
        if (payload.containsKey("priority")) {
            if (payload.containsKey("announcement_priority"))
                throw BusinessException.i18n("announcement.error.invalid_input");
            payload.put("announcement_priority", payload.remove("priority"));
        }
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setTargetRecordPid(pid);
        request.setOperationType(operation);
        request.setPayload(payload);
        request.setClientRequestId((String) operationId);
        if (revision != null) request.setExpectedVersion(((Number) revision).intValue());
        return ApiResponse.success(commandExecutor.execute(code, request).getData());
    }
}
