package com.auraboot.framework.tenant.controller.request;

import lombok.Data;

import java.util.List;

/**
 * Request for batch member status transitions.
 * Action values match the single-member status endpoint: active | inactive | suspended.
 */
@Data
public class BatchMemberStatusRequest {
    private List<String> memberPids;
    private String action;
    private String reason;
}
