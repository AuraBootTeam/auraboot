package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 页面定义验证结果
 */

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageDefinitionValidationResult {
    
    private Boolean valid;
    
    private String message;
    
    private List<String> errors;
    
    private List<String> warnings;
    
    private String validatedBy;
    
    private Long validatedAt;
    

}