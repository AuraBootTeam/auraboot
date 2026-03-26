package com.auraboot.framework.tenant.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 邀请实体 - 租户邀请新成员加入
 */
@Data
@TableName("ab_invitation")
public class Invitation {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;                    // 邀请ID
    private String pid;
    
    private Instant createdAt;             // 创建时间
    private Instant updatedAt;             // 更新时间
    
    private Long tenantId;              // 租户ID

    private Long inviterUserId;       // 邀请人用户ID
    
    private String inviteCode;          // 邀请码

    private String message;             // 邀请消息
    
    private String status;              // 状态：PENDING, ACCEPTED, REJECTED, EXPIRED


    private Instant expiredAt;             // 过期时间
    

    private Boolean deletedFlag = false; // 逻辑删除标记
    
    // 审计字段
    private Long createdBy;           // 创建人
    private Long updatedBy;           // 更新人

}