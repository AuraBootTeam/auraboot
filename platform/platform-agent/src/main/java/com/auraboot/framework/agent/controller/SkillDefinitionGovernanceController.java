package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.service.SkillDefinitionGovernanceService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.versioning.dto.DesignVersionDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Governance for the existing Skill editor; every lookup is scoped to the current tenant. */
@RestController
@RequestMapping("/api/agent/skill-definitions/{pid}")
@RequiredArgsConstructor
@RequirePermission("acp_agent_skill")
public class SkillDefinitionGovernanceController {
    private final SkillDefinitionGovernanceService governance;
    public record Decision(String expectedDraftHash, String expectedPublishedVersion, String description) {}

    @GetMapping("/governance")
    public ApiResponse<Map<String, Object>> inspect(@PathVariable String pid) {
        return ApiResponse.success(governance.inspect(pid));
    }
    @GetMapping("/versions/{versionPid}")
    public ApiResponse<DesignVersionDTO> version(@PathVariable String pid, @PathVariable String versionPid) {
        return ApiResponse.success(governance.version(pid, versionPid));
    }
    @PostMapping("/publish")
    @RequirePermission(MetaPermission.ACP_RUNTIME_MANAGE)
    public ApiResponse<Map<String, Object>> publish(@PathVariable String pid, @RequestBody Decision decision) {
        return ApiResponse.success(governance.publish(pid, decision.expectedDraftHash(), decision.expectedPublishedVersion(), decision.description()));
    }
    @PostMapping("/versions/{versionPid}/activate")
    @RequirePermission(MetaPermission.ACP_RUNTIME_MANAGE)
    public ApiResponse<Map<String, Object>> rollback(@PathVariable String pid, @PathVariable String versionPid, @RequestBody Decision decision) {
        return ApiResponse.success(governance.rollback(pid, versionPid, decision.expectedDraftHash(), decision.expectedPublishedVersion(), decision.description()));
    }
}
