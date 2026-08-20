package com.auraboot.framework.tenant.controller.request;

import lombok.Data;

/** Request for a member lifecycle transition with optional ownership transfer. */
@Data
public class MemberLifecycleRequest {
    private String action;
    private String reason;
    private String targetMemberPid;
}
