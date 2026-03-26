package com.auraboot.framework.webhook.controller;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.webhook.dto.WebhookCreateRequest;
import com.auraboot.framework.webhook.entity.WebhookDeliveryLog;
import com.auraboot.framework.webhook.entity.WebhookSubscription;
import com.auraboot.framework.webhook.mapper.WebhookDeliveryLogMapper;
import com.auraboot.framework.webhook.service.WebhookService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for webhook subscription management.
 *
 * @since 5.1.0
 */
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.SYS_WEBHOOK_MANAGE)
@Tag(name = "Webhooks", description = "Webhook configuration and delivery")
public class WebhookController {

    private final WebhookService webhookService;
    private final FieldEncryptionService fieldEncryptionService;
    private final WebhookDeliveryLogMapper deliveryLogMapper;

    @PostMapping
    public ApiResponse<WebhookSubscription> create(@Valid @RequestBody WebhookCreateRequest request) {
        return ApiResponse.success(maskWebhook(webhookService.create(request)));
    }

    @GetMapping
    public ApiResponse<List<WebhookSubscription>> list(
            @RequestParam(required = false) String eventType) {
        List<WebhookSubscription> subs = (eventType != null && !eventType.isBlank())
                ? webhookService.listByEventType(eventType)
                : webhookService.listAll();
        return ApiResponse.success(subs.stream().map(this::maskWebhook).toList());
    }

    @GetMapping("/{pid}")
    public ApiResponse<WebhookSubscription> getByPid(@PathVariable String pid) {
        WebhookSubscription sub = webhookService.getByPid(pid);
        if (sub == null) {
            return ApiResponse.error("Webhook not found: " + pid);
        }
        return ApiResponse.success(maskWebhook(sub));
    }

    @PutMapping("/{pid}")
    public ApiResponse<WebhookSubscription> update(@PathVariable String pid,
                                                    @Valid @RequestBody WebhookCreateRequest request) {
        return ApiResponse.success(maskWebhook(webhookService.update(pid, request)));
    }

    @DeleteMapping("/{pid}")
    public ApiResponse<Void> delete(@PathVariable String pid) {
        webhookService.delete(pid);
        return ApiResponse.success();
    }

    @PutMapping("/{pid}/enable")
    public ApiResponse<Void> enable(@PathVariable String pid) {
        webhookService.enable(pid);
        return ApiResponse.success();
    }

    @PutMapping("/{pid}/disable")
    public ApiResponse<Void> disable(@PathVariable String pid) {
        webhookService.disable(pid);
        return ApiResponse.success();
    }

    @PostMapping("/{pid}/test")
    public ApiResponse<Void> test(@PathVariable String pid,
                                   @RequestBody java.util.Map<String, Object> payload) {
        webhookService.testWebhook(pid, payload);
        return ApiResponse.success();
    }

    @GetMapping("/{pid}/deliveries")
    public ApiResponse<List<WebhookDeliveryLog>> deliveries(
            @PathVariable String pid,
            @RequestParam(defaultValue = "50") int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<WebhookDeliveryLog> logs = deliveryLogMapper.selectList(
                new LambdaQueryWrapper<WebhookDeliveryLog>()
                        .eq(WebhookDeliveryLog::getTenantId, tenantId)
                        .eq(WebhookDeliveryLog::getSubscriptionPid, pid)
                        .orderByDesc(WebhookDeliveryLog::getCreatedAt)
                        .last("LIMIT " + Math.min(limit, 200)));
        return ApiResponse.success(logs);
    }

    private WebhookSubscription maskWebhook(WebhookSubscription sub) {
        if (sub != null && sub.getSecret() != null) {
            sub.setSecret(fieldEncryptionService.mask(sub.getSecret()));
        }
        return sub;
    }
}
