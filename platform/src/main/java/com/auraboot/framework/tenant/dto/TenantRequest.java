package com.auraboot.framework.tenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;



@Data
public class TenantRequest {
    
    @NotBlank(message = "企业名称不能为空")
    @Size(max = 100, message = "企业名称长度不能超过100个字符")
    private String name;
    
    @Size(max = 100, message = "显示名称长度不能超过100个字符")
    private String displayName;

    @Size(max = 800, message = "LOGO 长度不能超过800个字符")
    private String logo; // 品牌Logo文件ID


    @Size(max = 50, message = "行业属性长度不能超过50个字符")
    private String industry; // 行业属性
    
    @Email(message = "联系邮箱格式不正确")
    @Size(max = 100, message = "联系邮箱长度不能超过100个字符")
    private String contactEmail;
    
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "联系电话格式不正确")
    private String contactPhone;
    
    @Size(max = 800, message = "官网地址长度不能超过200个字符")
    private String website; // 官网地址
    
    @Pattern(regexp = "^(?i)(active|inactive|suspended)$", message = "状态值不正确")
    private String status; // ACTIVE, INACTIVE, SUSPENDED
    
    @Size(max = 500, message = "描述长度不能超过500个字符")
    private String description; // 描述
}