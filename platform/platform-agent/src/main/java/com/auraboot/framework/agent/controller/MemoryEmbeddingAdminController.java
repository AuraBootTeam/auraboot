package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.service.MemoryEmbeddingRebuildService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Uses the existing tenant-admin interceptor and memory-admin permission; never accepts a caller tenant override. */
@RestController
@RequestMapping("/api/admin/memory/embeddings")
@RequirePermission(MetaPermission.ACP_MEMORY_ADMIN)
public class MemoryEmbeddingAdminController {
    private final MemoryEmbeddingRebuildService service;
    public MemoryEmbeddingAdminController(MemoryEmbeddingRebuildService service) { this.service = service; }

    @GetMapping
    public ApiResponse<Map<String, Object>> inventory(@RequestParam String agentCode,
            @RequestParam(defaultValue = "") String after, @RequestParam(defaultValue = "50") int limit) {
        try { return ApiResponse.ok(service.inventory(MetaContext.getCurrentTenantId(), agentCode, after, limit)); }
        catch (IllegalArgumentException invalid) { return ApiResponse.error(400, invalid.getMessage()); }
    }

    public record RebuildRequest(String agentCode, String expectedSpace, String reason) {}

    @GetMapping("/{pid}")
    public ApiResponse<Map<String, Object>> inspect(@PathVariable String pid, @RequestParam String agentCode) {
        try {
            var result = service.inspect(MetaContext.getCurrentTenantId(), agentCode, pid);
            return result == null ? ApiResponse.error(404, "not_found") : ApiResponse.ok(result);
        } catch (IllegalArgumentException invalid) { return ApiResponse.error(400, invalid.getMessage()); }
    }

    @PostMapping("/{pid}/rebuild")
    public ApiResponse<Map<String, Object>> rebuild(@PathVariable String pid, @RequestBody RebuildRequest request) {
        try {
            var result = service.rebuild(MetaContext.getCurrentTenantId(), MetaContext.getCurrentUserId(),
                    request.agentCode(), pid, request.expectedSpace(), request.reason());
            String outcome = String.valueOf(result.get("outcome"));
            if ("not_found_or_inactive".equals(outcome)) return ApiResponse.error(404, outcome);
            if (!"rebuilt".equals(outcome) && !"already_current".equals(outcome)) return ApiResponse.error(409, outcome);
            return ApiResponse.ok(result);
        } catch (IllegalArgumentException invalid) { return ApiResponse.error(400, invalid.getMessage()); }
    }
}
