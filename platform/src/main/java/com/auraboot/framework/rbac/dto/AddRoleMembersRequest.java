package com.auraboot.framework.rbac.dto;

import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

@Data
public class AddRoleMembersRequest {
    private List<String> memberPids;
    @Schema(nullable = true)
    private LocalDate effectiveDate;
    @Schema(nullable = true)
    private LocalDate expiryDate;
}
