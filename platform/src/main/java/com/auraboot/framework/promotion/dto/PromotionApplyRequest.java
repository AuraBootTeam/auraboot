package com.auraboot.framework.promotion.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for POST /api/promotions/{pid}/apply.
 * Reason is required when target env is locked (e.g. prod) — service-level check;
 * controller passes whatever the user provided (may be null/blank for staging).
 */
@Data
public class PromotionApplyRequest {

    @Size(max = 500, message = "reason must be 500 characters or fewer")
    private String reason;
}
