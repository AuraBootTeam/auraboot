package com.auraboot.framework.auth.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
public class AuthenticationResponse {
    private final String jwt;
    @JsonSerialize(using = ToStringSerializer.class)
    private final Long userId;
    private final String userPid;
    private final String username;
    @JsonSerialize(using = ToStringSerializer.class)
    private final Long tenantId;
    private final String tenantStatus;  // "member", "pending", "none"
    private boolean mustChangePassword = false;

    // Social OAuth merge fields
    /** True when social login discovers an existing account with matching email */
    private boolean mergeRequired = false;
    /** Temporary token for merge confirmation (only set when mergeRequired=true) */
    private String mergeToken;
    /** Which provider triggered the merge requirement */
    private String mergeProvider;

    public AuthenticationResponse(String jwt, Long userId, String userPid, String username, Long tenantId, String tenantStatus) {
        this.jwt = jwt;
        this.userId = userId;
        this.userPid = userPid;
        this.username = username;
        this.tenantId = tenantId;
        this.tenantStatus = tenantStatus;
    }

    public AuthenticationResponse(String jwt, Long userId, String userPid, String username) {
        this(jwt, userId, userPid, username, null, "none");
    }

    /**
     * Factory for a merge-required response (no JWT issued yet).
     */
    public static AuthenticationResponse mergeRequired(String mergeToken, String mergeProvider) {
        AuthenticationResponse response = new AuthenticationResponse(null, null, null, null, null, "none");
        response.setMergeRequired(true);
        response.setMergeToken(mergeToken);
        response.setMergeProvider(mergeProvider);
        return response;
    }
}