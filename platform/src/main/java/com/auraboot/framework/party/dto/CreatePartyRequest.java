package com.auraboot.framework.party.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreatePartyRequest {
    @NotBlank
    @Size(max = 100)
    @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]*")
    private String code;

    @NotBlank
    @Size(max = 200)
    private String displayName;

    @Size(max = 300)
    private String legalName;

    @Pattern(regexp = "organization|person")
    private String partyType = "organization";
}
