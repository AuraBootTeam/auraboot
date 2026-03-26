package com.auraboot.framework.notification.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.notification.dto.NotificationTemplateCreateRequest;
import com.auraboot.framework.notification.entity.NotificationTemplate;
import com.auraboot.framework.notification.service.NotificationTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for notification template management.
 *
 * @since 5.1.0
 */
@RestController
@RequestMapping("/api/notification-templates")
@RequiredArgsConstructor
public class NotificationTemplateController {

    private final NotificationTemplateService templateService;

    /**
     * Create a new template.
     * POST /api/notification-templates
     */
    @PostMapping
    public ApiResponse<NotificationTemplate> create(
            @Valid @RequestBody NotificationTemplateCreateRequest request) {
        return ApiResponse.success(templateService.create(request));
    }

    /**
     * List templates, optionally by channel.
     * GET /api/notification-templates?channel=EMAIL
     */
    @GetMapping
    public ApiResponse<List<NotificationTemplate>> list(
            @RequestParam(required = false) String channel) {
        if (channel != null && !channel.isBlank()) {
            return ApiResponse.success(templateService.listByChannel(channel));
        }
        return ApiResponse.success(templateService.listAll());
    }

    /**
     * Update a template.
     * PUT /api/notification-templates/{pid}
     */
    @PutMapping("/{pid}")
    public ApiResponse<NotificationTemplate> update(
            @PathVariable String pid,
            @Valid @RequestBody NotificationTemplateCreateRequest request) {
        return ApiResponse.success(templateService.update(pid, request));
    }

    /**
     * Delete a template.
     * DELETE /api/notification-templates/{pid}
     */
    @DeleteMapping("/{pid}")
    public ApiResponse<Void> delete(@PathVariable String pid) {
        templateService.delete(pid);
        return ApiResponse.success();
    }

    /**
     * Preview a rendered template.
     * POST /api/notification-templates/{code}/preview
     */
    @PostMapping("/{code}/preview")
    public ApiResponse<Map<String, Object>> preview(
            @PathVariable String code,
            @RequestBody Map<String, Object> variables) {
        String rendered = templateService.renderPreview(code, variables);
        return ApiResponse.success(Map.of("rendered", rendered));
    }
}
