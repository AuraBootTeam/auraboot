package com.auraboot.framework.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Public, non-sensitive description of an enabled login method.
 *
 * <p>The legacy {@code /channels} endpoint exposes only a code.  That is not enough for a client
 * to distinguish a tenant-defined OAuth/OIDC provider instance from LDAP or a built-in password
 * method.  This DTO intentionally contains presentation/routing metadata only; provider config and
 * secret references are never exposed.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginChannelOption {

    private String code;

    /** password | otp | oauth | ldap */
    private String kind;

    private String displayName;

    /** Concrete provider implementation type for federated methods; null for built-in methods. */
    private String providerType;
}
