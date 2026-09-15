package com.auraboot.framework.openplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("ab_external_application")
public class ExternalApplication {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String pid;
    private Long ownerTenantId;
    private String name;
    private String description;
    private String status;
    private String createdByPid;
    private Instant createdAt;
    private Instant updatedAt;
}
