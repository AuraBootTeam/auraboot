package com.auraboot.framework.auth.dto;

import lombok.Data;

@Data
public class AuthenticationRequest {
    private String identifier;
    private String email;
    private String password;

    public String resolveIdentifier() {
        if (identifier != null && !identifier.isBlank()) {
            return identifier.trim();
        }
        return email != null ? email.trim() : null;
    }
}
