package com.auraboot.framework.file.entity;

import com.auraboot.framework.file.constant.RelationType;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.Instant;

/**
 * 文件关联关系实体
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("file_relations")
public class FileRelationEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 关联关系唯一标识
     */
    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;

    //fixme remove all uuid
    /**
     * 文件ID
     */
    @TableField("file_id")
    private String fileId;

    /**
     * 关联实体类型
     */
    @TableField("entity_type")
    private String entityType;

    /**
     * 关联实体ID
     */
    @TableField("entity_id")
    private String entityId;

    /**
     * 关联字段名
     */
    @TableField("field_name")
    private String fieldName;

    /**
     * 关联类型
     */
    @TableField("relation_type")
    private RelationType relationType;

    /**
     * 排序序号
     */
    @TableField("sort_order")
    private Integer sortOrder;

    /**
     * 创建时间
     */
    @TableField(value = "created_time", fill = FieldFill.INSERT)
    private Instant createdTime;

    /**
     * 更新时间
     */
    @TableField(value = "updated_time", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedTime;

    /**
     * 删除标记
     */
    @TableField("deleted_flag")
    @TableLogic
    private Boolean deletedFlag;
}