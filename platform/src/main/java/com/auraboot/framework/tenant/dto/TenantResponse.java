package com.auraboot.framework.tenant.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TenantResponse {
    private Long id;
    private String pid;
    
    private String name;
    private String displayName;
    private String logo;
    private String industry;
    
    private String contactEmail;
    private String contactPhone;
    private String website;
    
    private String status;
    private String description;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    private Long createdBy;
    private Long updatedBy;
    
    // 统计信息
    private Long memberCount; // 成员数量
    private Long storeCount;  // 门店数量
}