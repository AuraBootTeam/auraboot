package com.auraboot.framework.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EmailLinkConfirmRequest(
        @NotBlank @Email @Size(max = 100) String email,
        @NotBlank @Pattern(regexp = "\\d{6}") String code) {
}
