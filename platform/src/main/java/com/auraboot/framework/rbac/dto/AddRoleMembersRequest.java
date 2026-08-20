package com.auraboot.framework.rbac.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class AddRoleMembersRequest {
    private List<String> memberPids;
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
}
