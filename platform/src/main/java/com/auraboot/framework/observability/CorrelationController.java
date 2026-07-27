package com.auraboot.framework.observability;

import com.auraboot.framework.observability.dto.CorrelationView;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified eagle-eye correlation API (SoT §2.3): one trace id → its command executions
 * + LLM cost + behavior events + audit events, joined across domains. Tenant-scoped.
 *
 * <p>Gated by {@link MetaPermission#SYSTEM_MANAGEMENT}, matching the
 * {@code /ops/troubleshooting} menu contract. The joined view surfaces command
 * payloads and cross-domain audit data and is not a tenant-wide general read API.
 */
@RestController
@RequestMapping("/api/observability/correlation")
@RequiredArgsConstructor
public class CorrelationController {

    private final CorrelationQueryService correlationQueryService;

    @GetMapping("/{traceId}")
    @RequirePermission(MetaPermission.SYSTEM_MANAGEMENT)
    public CorrelationView byTrace(@PathVariable String traceId) {
        return correlationQueryService.byTrace(traceId);
    }
}
