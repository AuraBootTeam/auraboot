package com.auraboot.framework.tenant.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 门店实体 - 租户下的门店/分支机构
 */
@Data
@TableName("ns_store")
public class Store {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;                    // 门店ID
    private String pid;
    
    private Instant createdAt;             // 创建时间
    private Instant updatedAt;             // 更新时间
    
    private Long tenantId;              // 所属租户ID

    private Long addressId;             // 地址ID
    
    private String name;                // 门店名称
    private String code;                // 门店编码
    private String type;                // 门店类型：FLAGSHIP, BRANCH, FRANCHISE等

//    private String contactPhone;        // 联系电话
//    private String contactEmail;        // 联系邮箱
//
//    private  String manager;            // json (userId,name,phone)
//
//    private String businessHours;       // 营业时间(JSON格式)
//    private String facilities;          // 设施信息(JSON格式)
//    private String settings;            // 门店配置(JSON格式)
//    private String description;         // 描述
      private String extension;  // 扩展,jsonb


    private String status;              // 状态：ACTIVE, INACTIVE, MAINTENANCE

    private Instant openDate;              // 开业日期
    private Instant closeDate;             // 关闭日期




    private Boolean deletedFlag = false; // 逻辑删除标记
    
    // 审计字段
    private Long createdBy;           // 创建人
    private Long updatedBy;           // 更新人
}