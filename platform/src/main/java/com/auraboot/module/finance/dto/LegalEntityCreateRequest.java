package com.auraboot.module.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Request payload for creating or updating a {@link com.auraboot.module.finance.entity.LegalEntity}.
 */
@Data
public class LegalEntityCreateRequest {

    @NotBlank
    @Size(max = 50)
    private String entityCode;

    @NotBlank
    @Size(max = 200)
    private String entityName;

    /** Parent entity id; null means this entity is the root of the group. */
    private Long parentId;

    @NotBlank
    @Size(min = 3, max = 3)
    private String currency;

    @DecimalMin("0.00")
    @DecimalMax("100.00")
    private BigDecimal ownershipPct;

    private Boolean isParent = Boolean.FALSE;
}
