package com.auraboot.framework.connector.jdbc.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class JdbcEndpointCreateRequest {
    @NotBlank
    @Size(max = 64)
    private String code;

    @Size(max = 128)
    private String name;

    @NotBlank
    @Pattern(regexp = "^(query|update)$", message = "operation must be 'query' or 'update'")
    private String operation;

    @NotBlank
    private String sqlTemplate;
}
