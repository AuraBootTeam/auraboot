package com.auraboot.framework.auth.dto;

import lombok.Data;

@Data
public class IdentityProviderSaveRequest {
    private String pid;
    private String applicationCode = "business-web";
    private String channelCode = "default-business-web";
    private String code;
    private String displayName;
    private String providerType;
    private String status = "active";
    private String config = "{}";
    private String secretRef;
    private Integer sortOrder = 100;
}
