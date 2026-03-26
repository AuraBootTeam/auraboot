package com.auraboot.framework.tenant.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.tenant.service.TenantPreferenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/tenant-preferences")
@RequiredArgsConstructor
public class TenantPreferenceController {

    private final TenantPreferenceService tenantPreferenceService;

    @GetMapping("/{key}")
    public ApiResponse<Map<String, JsonNode>> getPreference(@PathVariable String key) {
        Long tenantId = MetaContext.getCurrentTenantId();
        JsonNode value = tenantPreferenceService.getPreference(tenantId, key);
        return ApiResponse.success(Map.of("value", value != null ? value : NullNode.getInstance()));
    }

    @PutMapping("/{key}")
    public ApiResponse<Void> setPreference(@PathVariable String key, @RequestBody Map<String, JsonNode> body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        JsonNode value = body.get("value");
        tenantPreferenceService.setPreference(tenantId, key, value);
        return ApiResponse.success(null);
    }
}
