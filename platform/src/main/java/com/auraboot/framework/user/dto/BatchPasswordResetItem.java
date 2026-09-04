package com.auraboot.framework.user.dto;

/**
 * Per-user result of an admin batch password reset.
 * The temporary password is returned once — the same contract as the
 * single-user reset endpoint — and never persisted in plaintext.
 */
public record BatchPasswordResetItem(
    String userPid,
    String tempPassword
) {}
