package com.auraboot.framework.meta.dto;

import lombok.Data;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 命名查询查询请求DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryQueryRequest {

    /**
     * 页码
     */
    @Min(value = 1, message = "页码必须大于0")
    private Integer page = 1;

    /**
     * 页大小
     */
    @Min(value = 1, message = "页大小必须大于0")
    @Max(value = 1000, message = "页大小不能超过1000")
    private Integer size = 20;


    /**
     * 查询编码（模糊匹配）
     */
    @Size(max = 100, message = "查询编码长度不能超过100个字符")
    private String code;

    /**
     * 查询标题（模糊匹配）
     */
    @Size(max = 200, message = "查询标题长度不能超过200个字符")
    private String title;

    /**
     * 查询描述（模糊匹配）
     */
    @Size(max = 1000, message = "查询描述长度不能超过1000个字符")
    private String description;

    /**
     * 查询状态
     */
    private String status;

    /**
     * 关键词搜索
     */
    @Size(max = 200, message = "关键词长度不能超过200个字符")
    private String keyword;

    /**
     * 只查询启用的
     */
    private Boolean enabledOnly = false;

    /**
     * 创建时间开始
     */
    private LocalDateTime createdAtStart;

    /**
     * 创建时间结束
     */
    private LocalDateTime createdAtEnd;

    /**
     * 更新时间开始
     */
    private LocalDateTime updatedAtStart;

    /**
     * 更新时间结束
     */
    private LocalDateTime updatedAtEnd;

    /**
     * 排序字段
     */
    private String sortBy = "createdAt";

    /**
     * 排序方向
     */
    private String sortDirection = "desc";

    /**
     * 是否包含字段信息
     */
    private Boolean includeFields = false;

    /**
     * 标签过滤
     */
    private String tags;

    /**
     * 创建者过滤
     */
    private String createdBy;

    /**
     * 更新者过滤
     */
    private String updatedBy;
}