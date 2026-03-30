package com.auraboot.framework.meta.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.Map;

/**
 * 页面Schema历史记录实体类（简化版本）
 * 对应表：ab_page_schema_history
 *
 * 采用简化设计：
 * - 使用JSONB快照存储完整的页面Schema数据
 * - 避免字段冗余，提高存储效率
 * - 简化查询逻辑，通过JSONB操作符查询历史数据
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_page_schema_history")
public class PageSchemaHistory {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    /**
     * 关联的页面Schema PID
     */
    @TableField("page_pid")
    private String pid;

    /**
     * Full snapshot of the page schema at the time of the operation.
     * Contains: kind, profile, layout, blocks, meta_info, version info, etc.
     */
    @TableField(value = "snapshot", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> snapshot;

    /**
     * 操作类型
     * CREATE - 创建
     * UPDATE - 更新
     * PUBLISH - 发布
     * ARCHIVE - 归档
     * DELETE - 删除
     * RESTORE - 恢复
     */
    @TableField("op")
    private String op;

    /**
     * 操作人PID
     */
    @TableField("op_by")
    private String opBy;

    /**
     * 操作时间
     */
    @TableField("op_at")
    private Instant opAt;

    @TableField("created_at")
    private Instant createdAt;
}