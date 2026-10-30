package com.auraboot.framework.notification.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/** Client observations only; actor, tenant, app and external identity are server-owned. */
public record WechatSubscriptionObservationRequest(
        @NotEmpty @Size(max = 20) List<@Valid Choice> results,
        @NotBlank @Size(max = 120) @Pattern(regexp = "[A-Za-z0-9._:-]+") String idempotencyKey) {
    public WechatSubscriptionObservationRequest { if (results != null) results = List.copyOf(results); }
    @JsonAnySetter public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("SUBSCRIPTION_OBSERVATION_FIELD_INVALID"); }

    public record Choice(
            @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9_-]+") String templateId,
            @NotBlank @Pattern(regexp = "accept|reject|ban|filter") String status) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("SUBSCRIPTION_OBSERVATION_FIELD_INVALID"); }
    }
}
