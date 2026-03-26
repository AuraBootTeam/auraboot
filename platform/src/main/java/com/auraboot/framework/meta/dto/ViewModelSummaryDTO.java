package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * ViewModel summary DTO for listing and overview purposes.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ViewModelSummaryDTO {

    private String code;
    private String displayName;
    private String description;
    private String mode;
    private String baseEntityCode;
    private String namedQueryCode;
    private Integer fieldCount;
    private String status;
}
