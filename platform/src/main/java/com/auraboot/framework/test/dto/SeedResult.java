package com.auraboot.framework.test.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Response DTO for the test seed endpoint.
 * Contains all information needed by E2E tests (Playwright + iOS XCUITest).
 */
@Data
@Builder
public class SeedResult {
    private Long tenantId;
    private Long userId;
    private String jwt;
    private String email;
    private String tenantName;
    /** Test run ID for cross-platform coordination (format: {platform}_{timestamp}_{hex4}) */
    private String testRunId;
    /** Record IDs created during this seed (empty for idempotent re-seed) */
    @Builder.Default
    private java.util.List<String> recordIds = java.util.Collections.emptyList();
}
