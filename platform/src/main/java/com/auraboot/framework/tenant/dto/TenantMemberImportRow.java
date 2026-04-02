package com.auraboot.framework.tenant.dto;

import lombok.Data;

@Data
public class TenantMemberImportRow {

    private String name;
    private String email;
    private String phone;
    private String department;
    private String position;
}
