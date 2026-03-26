package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 字段绑定查询请求DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class FieldBindingQueryRequest {

    /**
     * 页码
     */
    private Integer pageNum = 1;

    /**
     * 页大小
     */
    private Integer pageSize = 20;

    /**
     * 模型ID
     */
    private Long modelId;

    /**
     * 字段ID
     */
    private Long fieldId;

    /**
     * 租户ID
     */
    private Long tenantId;

      

    

    /**
     * 绑定状态
     */
    private String bindingStatus;

    /**
     * 兼容性状态
     */
    private String compatibilityStatus;

    /**
     * 排序字段
     */
    private String sortBy;

    /**
     * 排序方向
     */
    private String sortDirection = "asc";
}