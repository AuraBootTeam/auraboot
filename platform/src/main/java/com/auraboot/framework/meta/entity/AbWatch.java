package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Per-user watch/follow subscription for a specific record.
 * Used by notification routing to include watchers as recipients.
 *
 * @since 6.1.0
 */
@Data
@TableName("ab_watch")
public class AbWatch {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("user_id")
    private Long userId;

    @TableField("model_code")
    private String modelCode;

    @TableField("record_id")
    private Long recordId;

    @TableField("created_at")
    private Instant createdAt;
}
