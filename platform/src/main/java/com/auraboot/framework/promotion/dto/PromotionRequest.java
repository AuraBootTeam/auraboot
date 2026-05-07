package com.auraboot.framework.promotion.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * Create-promotion request body. Source/target referenced by env id (already resolved by caller
 * via /api/admin/environments listing).
 */
@Data
public class PromotionRequest {

    @NotNull
    private Long sourceEnvId;

    @NotNull
    private Long targetEnvId;

    /** At least one unit. PoC: only PAGE_SCHEMA resourceType is accepted. */
    @NotEmpty
    private List<PromotionUnitDto> units;

    @Data
    public static class PromotionUnitDto {
        @NotNull
        private String resourceType;

        @NotNull
        private String resourcePid;

        /** Optional; if null, service captures the current source version of the resource. */
        private Integer sourceVersion;

        private Integer sortOrder;
    }
}
