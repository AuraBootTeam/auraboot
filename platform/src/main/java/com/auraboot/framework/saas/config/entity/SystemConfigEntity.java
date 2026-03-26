package com.auraboot.framework.saas.config.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.Instant;

@Data
@TableName("ab_system_config")
public class SystemConfigEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String pid;
    private String configScope;
    private String configKey;
    private String configValue;
    private String valueType;
    private String description;
    private Boolean isReadonly;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
