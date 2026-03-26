package com.auraboot.framework.user.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.user.service.UserPreferenceService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/user-preferences")
@RequiredArgsConstructor
public class UserPreferenceController {

    private final UserPreferenceService userPreferenceService;

    @GetMapping("/{key}")
    public ApiResponse<Map<String, JsonNode>> getPreference(@PathVariable String key) {
        Long userId = MetaContext.getCurrentUserId();
        JsonNode value = userPreferenceService.getPreference(userId, key);
        return ApiResponse.success(Map.of("value", value != null ? value : com.fasterxml.jackson.databind.node.NullNode.getInstance()));
    }

    @PutMapping("/{key}")
    public ApiResponse<Void> setPreference(@PathVariable String key, @RequestBody Map<String, JsonNode> body) {
        Long userId = MetaContext.getCurrentUserId();
        JsonNode value = body.get("value");
        userPreferenceService.setPreference(userId, key, value);
        return ApiResponse.success(null);
    }
}
