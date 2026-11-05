package com.auraboot.framework.permission.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;

/** One role-local draft; no partial requests or implicit baseline replacement. */
public record RoleAuthorizationDraftRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedRevision,
        @NotNull @Size(max = 5000) Set<String> selectedCapabilityCodes,
        @Size(max = 5000) Set<String> revokedPartialCapabilityCodes,
        boolean defaultScopeChanged,
        @Schema(nullable = true) String defaultScopeType,
        boolean applyDefaultToCurrentGrants,
        @Valid @Size(max = 1000) List<DataScopeUpdateRequest> scopeChanges,
        @Size(max = 1000) Set<String> fieldPolicyPids,
        @Size(max = 5000) Set<@NotBlank @Size(max = 32) String> menuPids) {
    public RoleAuthorizationDraftRequest(String revision, Set<String> selected, Set<String> revoked,
            boolean changed, String scope, boolean applyDefault, List<DataScopeUpdateRequest> scopeChanges,Set<String> fieldPolicyPids) {
        this(revision,selected,revoked,changed,scope,applyDefault,scopeChanges,fieldPolicyPids,null);
    }
    public RoleAuthorizationDraftRequest(String revision, Set<String> selected, Set<String> revoked,
            boolean changed, String scope, boolean applyDefault, List<DataScopeUpdateRequest> scopeChanges) {
        this(revision, selected, revoked, changed, scope, applyDefault, scopeChanges, null, null);
    }
}
