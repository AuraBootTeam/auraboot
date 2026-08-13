package com.auraboot.framework.rbac.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class AssignRolesByPidRequest {
    private String memberPid;
    private List<String> rolePids;
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
}
