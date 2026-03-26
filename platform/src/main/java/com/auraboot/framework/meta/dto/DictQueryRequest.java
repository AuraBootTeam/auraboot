package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 字典查询请求DTO
 * 继承AbstractQueryRequest获得通用查询字段
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DictQueryRequest extends AbstractQueryRequest {

    /**
     * 字典编码
     */
    private String code;

    /**
     * 字典名称
     */
    private String name;

    /**
     * 字典类型
     */
    private String dictType;

    /**
     * 数据源类型
     */
    private String sourceType;

    /**
     * 版本策略
     */
    private String versionStrategy;

    /**
     * 状态
     */
    private String status;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 是否系统字典
     */
    private Boolean isSystem;

    /**
     * 是否已发布
     */
    private Boolean isPublished;

    /**
     * 标签
     */
    private String tags;

    /**
     * 创建者
     */
    private String createdBy;

    /**
     * 更新者
     */
    private String updatedBy;

    /**
     * 分页页码
     */
    private Integer pageNum = 1;

    /**
     * 分页大小
     */
    private Integer pageSize = 10;

    /**
     * 排序字段
     */
    private String sortField = "created_at";

    /**
     * 排序方向
     */
    private String sortOrder = "desc";
}