package com.auraboot.framework.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailLinkSendCodeRequest(
        @NotBlank @Email @Size(max = 100) String email) {
}
