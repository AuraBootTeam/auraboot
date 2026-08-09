package com.auraboot.framework.auth.dto;

import lombok.Data;

import java.time.Instant;

/** Safe projection used to render canonical federated links for the current user. */
@Data
public class ExternalIdentityLinkSummary {
    private String pid;
    private String provider;
    private String displayName;
    private String avatarUrl;
    private String email;
    private Instant linkedAt;
}
