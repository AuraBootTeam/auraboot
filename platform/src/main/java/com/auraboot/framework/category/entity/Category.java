package com.auraboot.framework.category.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * 类目实体 - 支持两级树形结构的通用类目
 */
@Data
@TableName("ab_category")
public class Category {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;                    // 类目ID

    private String pid;                 // 业务ID(ULID)
    private Instant createdAt;          // 创建时间
    private Instant updatedAt;          // 更新时间

    private Long tenantId;              // 租户ID
    private Long parentId;              // 父类目ID
    private Integer level;              // 层级(1或2)

    private String name;                // 类目名称
    private String code;                // 类目编码(租户级唯一)
    private String categoryType;        // 类目类型

    private Integer sortOrder;          // 排序权重
    private String icon;                // 图标
    private String color;               // 颜色标识
    private Boolean visible = true;     // 是否显示

    private String status;              // 状态(ACTIVE/INACTIVE)

    @TableField("is_leaf")
    private boolean leaf = false;     // 是否叶子节点

    private String description;         // 描述
    private String extra;               // JSON扩展字段

    @TableLogic
    private Boolean deletedFlag = false; // 逻辑删除标记

    // 审计字段
    private Long createdBy;             // 创建人
    private Long updatedBy;             // 更新人
}
