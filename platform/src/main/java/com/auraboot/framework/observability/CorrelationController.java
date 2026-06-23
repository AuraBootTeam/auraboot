package com.auraboot.framework.observability;

import com.auraboot.framework.observability.dto.CorrelationView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified eagle-eye correlation API (SoT §2.3): one trace id → its LLM cost +
 * behavior events + audit events, joined across domains. Tenant-scoped.
 */
@RestController
@RequestMapping("/api/observability/correlation")
@RequiredArgsConstructor
public class CorrelationController {

    private final CorrelationQueryService correlationQueryService;

    @GetMapping("/{traceId}")
    public CorrelationView byTrace(@PathVariable String traceId) {
        return correlationQueryService.byTrace(traceId);
    }
}
