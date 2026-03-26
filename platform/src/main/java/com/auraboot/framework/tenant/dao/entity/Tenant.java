package com.auraboot.framework.tenant.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 租户实体 - 多租户架构的核心概念
 * 表示一个客户组织
 */
@Data
@TableName("ab_tenant")
public class Tenant {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;                    // 租户ID
    
    private String pid;                 // 业务ID(ULID)
    
    private Instant createdAt;             // 创建时间
    private Instant updatedAt;             // 更新时间
    
    private String name;                // 组织名称
    private String displayName;         // 显示名称
    private String logo;                // 品牌Logo文件ID
    private String industry;            // 行业属性

    private String contactEmail;        // 联系邮箱
    private String contactPhone;        // 联系电话
    private String website;             // 官网地址
    
    private String status;              // Status: ACTIVE, INACTIVE, SUSPENDED

    private String description;         // Description

    private String defaultCurrency;     // ISO 4217 currency code (e.g. USD, CNY, EUR)
    private String timezone;            // IANA timezone ID (e.g. Asia/Shanghai, UTC)
    
    private Boolean deletedFlag = false; // 逻辑删除标记
    
    // 审计字段
    private Long createdBy;           // 创建人
    private Long updatedBy;           // 更新人
}