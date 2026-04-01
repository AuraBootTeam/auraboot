package com.auraboot.framework.tenant.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.auraboot.framework.tenant.typehandler.JsonStringTypeHandler;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * 租户成员实体 - 用户与租户的关联关系
 */
@Data
@TableName("ab_tenant_member")
public class TenantMember {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;                    // 关联ID
    
    private String pid;                 // 业务ID(ULID)
    
    private Instant createdAt;             // 创建时间
    private Instant updatedAt;             // 更新时间
    
    private Long tenantId;              // 租户ID
    private Long userId;              // 用户ID
    private Long employeeId;          // 关联员工ID

    private String status;              // 状态：ACTIVE, INACTIVE, PENDING
    
//    private String jobTitle;            // 职位
//    private String department;          // 部门
//    private String workLocation;        // 工作地点
//
    private Instant joinDate;              // 加入日期
    private Instant leaveDate;             // 离职日期

    @TableField(value = "permissions", typeHandler = JsonStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String permissions;         // 特殊权限(JSON格式)
    @TableField(value = "settings", typeHandler = JsonStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String settings;            // 成员配置(JSON格式)
    @TableField(value = "extensions", typeHandler = JsonStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private  String extensions;         // 扩展
    
    private Boolean deletedFlag = false; // 逻辑删除标记
    
    // 审计字段
    private Long createdBy;           // 创建人
    private Long updatedBy;           // 更新人
}
