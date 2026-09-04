package com.auraboot.framework.user.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request for admin batch password reset. Hard-capped so one call
 * cannot trigger an unbounded number of credential rotations.
 */
public record BatchPasswordResetRequest(
    @NotEmpty
    @Size(max = 100)
    List<String> userPids
) {}
