package com.auraboot.framework.openplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("ab_application_installation")
public class ApplicationInstallation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String pid;
    private Long tenantId;
    private Long applicationId;
    private String environment;
    private Integer rateLimitPerMinute;
    private String status;
    private String installedByPid;
    private Instant installedAt;
    private Instant updatedAt;
}
