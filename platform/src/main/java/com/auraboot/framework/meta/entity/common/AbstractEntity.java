package com.auraboot.framework.meta.entity.common;

import com.auraboot.framework.application.database.mybatis.ExtensionTypeHandler;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * 基础实体类
 * 包含所有实体的共性字段
 */
@Data
public abstract class AbstractEntity {
    
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    @TableField("pid")
    private String pid;
    
    @TableField("tenant_id")
    private Long tenantId;



    @TableField(value = "extension", typeHandler = ExtensionTypeHandler.class, jdbcType = JdbcType.OTHER)
    private ExtensionBean extension;

    @TableField("status")
    private String status;

    @TableField("deleted_flag")
    @TableLogic(value = "false", delval = "true")
    private Boolean deletedFlag;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

}