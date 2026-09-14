package com.auraboot.framework.openplatform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class OpenPlatformDtos {
    private OpenPlatformDtos() {
    }

    public record CreateApplicationRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 1000) String description) {
    }

    public record InstallApplicationRequest(
            @NotBlank @Pattern(regexp = "development|staging|production") String environment,
            @NotEmpty Set<@Pattern(regexp = "[a-z][a-z0-9._:-]{2,159}") String> scopes,
            @Min(1) @Max(100000) Integer rateLimitPerMinute) {
    }

    public record UpdateInstallationScopesRequest(
            @NotEmpty Set<@Pattern(regexp = "[a-z][a-z0-9._:-]{2,159}") String> scopes) {
    }

    public record ApplicationView(String pid, String name, String description, String status,
                                  Instant createdAt, List<InstallationView> installations) {
    }

    public record InstallationView(String pid, String environment, String status,
                                   Set<String> scopes, Integer rateLimitPerMinute, Instant installedAt) {
    }

    public record CredentialSecret(String credentialPid, String clientId, String clientSecret,
                                   Instant createdAt, Instant expiresAt) {
    }

    public record CredentialView(String pid, String clientId, String status,
                                 Instant createdAt, Instant expiresAt, Instant lastUsedAt) {
    }

    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn,
            String scope) {
    }
}
