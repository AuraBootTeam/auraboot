package com.auraboot.framework.meta.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.RecordCapabilities;
import com.auraboot.framework.meta.service.RecordCapabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Record Capability API controller.
 * <p>
 * Exposes {@code GET /api/records/{modelCode}/{recordId}/capabilities} as defined in
 * {@code docs/system-reference/subsystems/50-Capability动作能力接口.md}.
 * <p>
 * Returns the dynamic list of actions and tabs available for a specific record,
 * based on the record's current state, user permissions, command definitions,
 * platform, and usage context.
 * <p>
 * Supports conditional requests via {@code If-None-Match} / {@code ETag} headers.
 *
 * @author AuraBoot Team
 * @since 3.1.0
 */
@Slf4j
@RestController
@RequestMapping("/api/records")
@RequiredArgsConstructor
@Tag(name = "Record Capabilities", description = "Dynamic action and tab resolution for record detail/list/inbox")
public class RecordCapabilityController {

    private final RecordCapabilityService recordCapabilityService;

    /**
     * Get available capabilities for a specific record.
     * <p>
     * The response includes:
     * <ul>
     *   <li>Sorted, filtered action list (capabilities)</li>
     *   <li>Tab list with visibility (detail context only)</li>
     *   <li>Current record state</li>
     *   <li>ETag for conditional caching</li>
     * </ul>
     *
     * @param modelCode model code, e.g. "crm_opportunity"
     * @param recordId  primary key of the record
     * @param platform  "web" or "mobile" (from X-Platform header or query param, default "web")
     * @param context   usage context: "detail" (default), "list", "inbox"
     * @param ifNoneMatch ETag from a previous response for conditional request
     * @return capabilities response with ETag header
     */
    @GetMapping("/{modelCode}/{recordId}/capabilities")
    @Operation(
            summary = "Get record capabilities",
            description = "Returns available actions and tabs for a record, filtered by user permissions, "
                    + "record state, platform, and context. Supports ETag-based conditional requests."
    )
    public ResponseEntity<ApiResponse<RecordCapabilities>> getRecordCapabilities(
            @Parameter(description = "Model code, e.g. crm_opportunity")
            @PathVariable String modelCode,

            @Parameter(description = "Record primary key")
            @PathVariable String recordId,

            @Parameter(description = "Platform: web or mobile")
            @RequestHeader(value = "X-Platform", defaultValue = "web") String platform,

            @Parameter(description = "Usage context: detail, list, or inbox")
            @RequestParam(defaultValue = "detail") String context,

            @Parameter(description = "ETag from previous response for conditional request", hidden = true)
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        log.debug("Record capabilities request: model={}, record={}, platform={}, context={}",
                modelCode, recordId, platform, context);

        Long userId = MetaContext.getCurrentUserId();

        RecordCapabilities capabilities = recordCapabilityService.getRecordCapabilities(
                modelCode, recordId, platform, context, userId);

        // ETag conditional: if client's ETag matches, return 304
        String etag = capabilities.getEtag();
        if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
            return ResponseEntity.status(304).build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=30")
                .body(ApiResponse.success(capabilities));
    }
}
