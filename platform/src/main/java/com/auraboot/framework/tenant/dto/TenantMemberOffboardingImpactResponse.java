package com.auraboot.framework.tenant.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** PID-safe ownership impact shown before a destructive member lifecycle change. */
@Data
@Builder
public class TenantMemberOffboardingImpactResponse {
    private String memberPid;
    private String targetMemberPid;
    private long ownedResourceCount;
    private boolean transferRequired;
    private List<ResourceImpact> resources;

    @Data
    @Builder
    public static class ResourceImpact {
        private String resourceType;
        private String displayName;
        private long ownedCount;
        private boolean transferable;
    }
}
