package com.auraboot.framework.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request DTO for sending a verification code.
 *
 * @since 7.0.0
 */
@Data
public class SendCodeRequest {

    /** Phone number or email address */
    @NotBlank(message = "Target is required")
    private String target;

    /** LOGIN | BIND | RESET_PASSWORD | DEACTIVATION */
    @NotBlank(message = "Type is required")
    private String type;
}
