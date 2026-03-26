package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 字段定义查询请求DTO
 * 用于字段定义分页查询的参数封装
 */
@Data
public class MetaFieldQueryRequest {

    /**
     * 页码
     */
    private Integer pageNum;

    /**
     * 页面大小
     */
    private Integer pageSize;

    /**
     * 字段键
     */
    private String code;

    /**
     * 数据类型
     */
    private String dataType;

    /**
     * 数据源ID
     */
    private Long dataSourceId;

    /**
     * 状态
     */
    private String status;

      

    

    /**
     * 关键词搜索
     */
    private String keyword;

    /**
     * 是否只查询当前版本
     */
    private Boolean currentOnly;

    /**
     * 创建者
     */
    private String createdBy;

    /**
     * 开始时间
     */
    private String startTime;

    /**
     * 结束时间
     */
    private String endTime;

    /**
     * 排序字段
     */
    private String sortField;

    /**
     * 排序方向
     */
    private String sortOrder;

    /**
     * 构造函数
     */
    public MetaFieldQueryRequest() {
        this.pageNum = 1;
        this.pageSize = 20;
          
        
        this.currentOnly = true;
        this.sortField = "createdAt";
        this.sortOrder = "desc";
    }

    /**
     * 获取页码（兼容方法）
     */
    public Integer getPage() {
        return this.pageNum;
    }

    /**
     * 获取页面大小（兼容方法）
     */
    public Integer getSize() {
        return this.pageSize;
    }

    /**
     * 获取租户ID（兼容方法）
     */
    public Long getTenantId() {
        return null; // 从线程上下文获取
    }

    /**
     * 获取是否只查询当前版本（兼容方法）
     */
    public Boolean getIsCurrentOnly() {
        return this.currentOnly;
    }
}