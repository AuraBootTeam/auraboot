package com.auraboot.framework.connector.jdbc.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class JdbcConnectorCreateRequest {
    @NotBlank
    @Size(max = 128)
    private String name;

    @NotBlank
    @Pattern(regexp = "^jdbc:(mysql|postgresql)://.+$",
             message = "jdbcUrl must start with jdbc:mysql:// or jdbc:postgresql://")
    private String jdbcUrl;

    @NotBlank
    @Size(max = 128)
    private String username;

    @NotBlank
    private String password;

    @Min(1) @Max(50)
    private Integer maxPoolSize = 5;

    @Min(1000) @Max(120000)
    private Integer connectionTimeoutMs = 30000;

    private Boolean enabled = Boolean.TRUE;
}
