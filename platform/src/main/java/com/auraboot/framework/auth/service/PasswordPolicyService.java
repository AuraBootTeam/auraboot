package com.auraboot.framework.auth.service;

import java.util.List;

/**
 * Password strength validation service.
 */
public interface PasswordPolicyService {

    /**
     * Validate password against configured policy rules.
     * @param password the plaintext password
     * @return list of validation error messages; empty if valid
     */
    List<String> validate(String password);

    /**
     * Check if password meets all policy requirements.
     * @param password the plaintext password
     * @return true if valid
     */
    default boolean isValid(String password) {
        return validate(password).isEmpty();
    }
}
