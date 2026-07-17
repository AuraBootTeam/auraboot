package com.auraboot.framework.tenant.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Result of {@code admin:create_member}.
 *
 * @author AuraBoot Team
 * @since 4.0.0
 */
@Data
@Builder
public class TenantMemberCreateResult {

    private String memberPid;

    private String userPid;

    private String email;

    /**
     * The password the member can log in with — echoed back whether it was
     * supplied or generated, because an admin who creates an account and is not
     * told the password has not created a usable account.
     */
    private String password;

    /** True when the password was generated here rather than supplied. */
    private boolean passwordGenerated;

    /** Role codes actually granted. */
    private List<String> assignedRoles;
}
