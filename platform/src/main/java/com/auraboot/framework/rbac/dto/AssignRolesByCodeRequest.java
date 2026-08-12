package com.auraboot.framework.rbac.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class AssignRolesByCodeRequest {
    private String memberPid;
    private List<String> roleCodes;
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
}
