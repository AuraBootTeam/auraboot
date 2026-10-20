package com.auraboot.framework.openplatform.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;

public final class IntegrationTokenDtos {
    private IntegrationTokenDtos() { }

    public record Create(@NotBlank @Size(max = 160) String name,
                         @Pattern(regexp = "fixed|never") String expirationMode, Instant expiresAt) { }
    public record Rotate(@Pattern(regexp = "fixed|never") String expirationMode, Instant expiresAt,
                         @Min(0) @Max(10080) Integer graceMinutes) { }
    public record View(String pid, String name, String status, String expirationMode,
                       Instant issuedAt, Instant expiresAt, Instant lastUsedAt,
                       Instant acceptUntil, Instant rotatedAt, String replacedByPid) { }
    public record Secret(String appId, String installationPid, String token, View metadata) {
        @Override public String toString() {
            return "IntegrationTokenSecret[appId=" + appId + ", token=[REDACTED], metadata=" + metadata + "]";
        }
    }
    public record Rotation(Secret credential, String replacedTokenPid, Instant replacedTokenAcceptUntil) { }
}
