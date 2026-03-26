package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 字典项查询请求DTO
 * 用于字典项分页查询的参数封装
 */
@Data
public class DictItemQueryRequest {

    /**
     * 页码
     */
    private Integer pageNum;

    /**
     * 页面大小
     */
    private Integer pageSize;

    /**
     * 字典PID
     */
    private String dictPid;

    /**
     * 字典编码
     */
    private String dictCode;

    /**
     * 字典项值
     */
    private String value;

    /**
     * 字典项标签
     */
    private String label;

    /**
     * 父级值（级联字典使用）
     */
    private String parentValue;

    /**
     * 状态
     */
    private String status;

      

    

    /**
     * 关键词搜索
     */
    private String keyword;

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
    public DictItemQueryRequest() {
        this.pageNum = 1;
        this.pageSize = 20;
          
        
        this.sortField = "sortNo";
        this.sortOrder = "asc";
    }
}