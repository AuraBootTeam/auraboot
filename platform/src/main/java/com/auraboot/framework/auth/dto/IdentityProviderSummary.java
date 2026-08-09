package com.auraboot.framework.auth.dto;

import lombok.Data;

@Data
public class IdentityProviderSummary {
    private String pid;
    private Long tenantId;
    private String applicationCode;
    private String code;
    private String displayName;
    private String providerType;
    private String status;
    private String config;
    private String secretRef;
    private String channelCode;
    private String bindingStatus;
    private Integer sortOrder;
    private boolean editable;
}
