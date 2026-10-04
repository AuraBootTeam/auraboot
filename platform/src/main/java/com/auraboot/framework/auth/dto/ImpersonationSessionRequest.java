package com.auraboot.framework.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ImpersonationSessionRequest(
        @NotBlank @Size(max = 26) String targetMemberPid,
        @NotBlank @Pattern(regexp = "phone|wechat|email|offline|other") String authorizationMethod,
        @NotBlank @Size(max = 500) String reason,
        @Size(max = 200) String reference,
        @NotBlank @Pattern(regexp = "web|wechat_mini") String clientType) {
}
