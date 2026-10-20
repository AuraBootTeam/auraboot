package com.auraboot.framework.view.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/**
 * Body for {@code POST /api/views/{viewPid}/pin}. M2 uses only {@code order}
 * (personal scope). {@code scope}/{@code teamId} are accepted now so the M3
 * team-pin slice needs no request-shape change.
 */
@Data
public class ChipPinRequest {

    /** 'personal' (default) or 'team'. */
    @Schema(nullable = true)
    private String scope;

    /** Team PID when scope is 'team' (M3). */
    @Schema(nullable = true)
    private String teamId;

    /** Display order among the user's chip pins. */
    @Schema(nullable = true)
    private Integer order;
}
