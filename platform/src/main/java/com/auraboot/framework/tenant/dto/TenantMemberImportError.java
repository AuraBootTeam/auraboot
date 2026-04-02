package com.auraboot.framework.tenant.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantMemberImportError {

    private int rowNumber;
    private String name;
    private String email;
    private String reason;
}
