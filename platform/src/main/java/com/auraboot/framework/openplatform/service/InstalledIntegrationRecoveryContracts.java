package com.auraboot.framework.openplatform.service;

import jakarta.validation.constraints.*;
import java.time.Instant;

/** Payload-free decisions for explicit tenant-admin receipt recovery. */
public final class InstalledIntegrationRecoveryContracts {
    private InstalledIntegrationRecoveryContracts() {}
    public record Plan(String requestPid, String envelopeSha256, String bindingSha256,
                       String previousCommandSha256, String previousProviderSha256,
                       String commandSha256, String providerSha256, int expectedReplayCount) {}
    public record Decision(
            @NotBlank @Size(max=100) String requestPid,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String envelopeSha256,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String bindingSha256,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String previousCommandSha256,
            @NotBlank @Pattern(regexp="sha256:[a-f0-9]{64}") String previousProviderSha256,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String commandSha256,
            @NotBlank @Pattern(regexp="sha256:[a-f0-9]{64}") String providerSha256,
            @Min(0) int expectedReplayCount,
            @NotBlank @Size(max=512) String reason) {}
    public record Result(String recoveryPid, String eventId, String requestPid, String status,
                         int replayCount, String requestedBy, String reason, Instant requestedAt) {}
}
