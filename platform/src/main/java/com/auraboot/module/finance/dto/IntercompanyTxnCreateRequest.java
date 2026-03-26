package com.auraboot.module.finance.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request payload for recording an intercompany transaction.
 */
@Data
public class IntercompanyTxnCreateRequest {

    @NotNull
    private Long fromEntityId;

    @NotNull
    private Long toEntityId;

    @NotNull
    private LocalDate txnDate;

    @NotBlank
    @Size(max = 50)
    private String txnType;

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal amount;

    @NotBlank
    @Size(min = 3, max = 3)
    private String currency;

    @Size(max = 500)
    private String description;
}
