package com.auraboot.framework.tenant.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Tenant-scoped identities for collaborator pickers, without private membership details. */
public record MemberSearchOption(String pid, String status, @Schema(nullable = true) UserIdentity user) {

    public record UserIdentity(
            String pid, String username, @Schema(nullable = true) String email,
            @Schema(nullable = true) String realName, @Schema(nullable = true) String avatar) {
    }
}
