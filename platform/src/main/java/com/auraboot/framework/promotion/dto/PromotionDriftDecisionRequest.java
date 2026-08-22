package com.auraboot.framework.promotion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Exact-fingerprint human decision for one promotion drift unit. */
@Data
public class PromotionDriftDecisionRequest {

    @NotBlank
    @Pattern(regexp = "^[a-f0-9]{64}$")
    private String expectedFingerprint;

    @NotBlank
    @Pattern(regexp = "^(REBASE|BACKPORT|KEEP_OVERRIDE|OVERWRITE)$")
    private String decision;

    @NotBlank
    @Size(max = 500)
    private String reason;
}
