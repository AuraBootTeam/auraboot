package com.auraboot.framework.tenant.dto;

/** Tenant-scoped identities for collaborator pickers, without private membership details. */
public record MemberSearchOption(String pid, String status, UserIdentity user) {

    public record UserIdentity(
            String pid, String username, String email, String realName, String avatar) {
    }
}
