package com.auraboot.framework.openplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("ab_external_application_member")
public class ExternalApplicationMember {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long applicationId;
    private String userPid;
    private String role;
    private String createdByPid;
    private Instant createdAt;
    private String updatedByPid;
    private Instant updatedAt;
}
