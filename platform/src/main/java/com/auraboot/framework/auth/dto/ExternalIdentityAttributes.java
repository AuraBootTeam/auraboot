package com.auraboot.framework.auth.dto;

/**
 * Allow-listed external identity attributes persisted with an identity link.
 */
public record ExternalIdentityAttributes(
        String subject,
        String username,
        String email,
        String claimsJson) {
}
