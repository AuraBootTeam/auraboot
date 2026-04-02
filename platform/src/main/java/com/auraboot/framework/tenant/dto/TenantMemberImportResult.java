package com.auraboot.framework.tenant.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class TenantMemberImportResult {

    private int totalRows;
    private int successCount;
    private int errorCount;
    private int existingUserBoundCount;
    private int invitedCount;
    private int employeeCreatedCount;
    private List<TenantMemberImportError> errors;
}
