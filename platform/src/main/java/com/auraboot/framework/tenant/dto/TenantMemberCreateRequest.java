package com.auraboot.framework.tenant.dto;

import lombok.Data;

import java.util.List;

/**
 * Payload for {@code admin:create_member} — an administrator creating a tenant
 * member directly.
 *
 * <p>Before this existed, the only ways to get a second user into a fresh AuraBoot
 * were self-registration (disabled by default) or the employee route: create a
 * department, create a position, create an employee (both are required fields),
 * then provision an account from it. Five steps, and an org structure you may not
 * want, just to have somebody to test a permission against.
 *
 * <p>That mattered more than it sounds: AuraBoot's whole argument is that every
 * write passes one permission and audit path. You cannot demonstrate a permission
 * with one user — and the one user you get is an admin, who is never refused
 * anything.
 *
 * @author AuraBoot Team
 * @since 4.0.0
 */
@Data
public class TenantMemberCreateRequest {

    /** Display name. Required. */
    private String name;

    /** Login email. Required, and must not already be a member of this tenant. */
    private String email;

    /** Optional mobile number. */
    private String phone;

    /**
     * Initial password. Optional — one is generated and returned if omitted.
     * Either way the member is created ACTIVE and can log in immediately: an
     * admin creating an account on purpose is a different act from an invitation
     * that still needs the invitee to confirm.
     */
    private String password;

    /**
     * Role codes to grant, e.g. {@code ["tenant_member"]}. Optional — a member
     * with no roles here still gets whatever the tenant baseline grants, which
     * is exactly the account you want when the point is to be refused something.
     */
    private List<String> roleCodes;
}
