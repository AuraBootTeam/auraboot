package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

/**
 * 数据字典实体与字段关联创建请求DTO
 */
@Data
public class DictEntityFieldCreateRequest   {

    /**
     * 关联的数据字典实体ID
     */
    @NotNull(message = "实体ID不能为空")
    private Long entityId;

    /**
     * 关联的字段定义ID
     */
    @NotNull(message = "字段ID不能为空")
    private Long fieldId;

    /**
     * 字段在实体中的排序
     */
    @Min(value = 0, message = "字段排序不能小于0")
    private Integer fieldOrder;

    /**
     * 批量创建关联请求
     */
    @Data
    public static class BatchCreateRequest {
        @NotNull(message = "实体ID不能为空")
        private Long entityId;
        
        @NotNull(message = "字段ID列表不能为空")
        private java.util.List<Long> fieldIds;
        
        /**
         * 是否自动排序
         */
        private Boolean autoOrder = true;
    }

    /**
     * 字段排序更新请求
     */
    @Data
    public static class FieldOrderUpdateRequest {
        @NotNull(message = "实体ID不能为空")
        private Long entityId;
        
        @NotNull(message = "字段排序映射不能为空")
        private java.util.Map<Long, Integer> fieldOrderMap;
        
        @NotNull(message = "字段排序列表不能为空")
        private java.util.List<FieldOrderInfo> fieldOrders;
        
        /**
         * 字段排序信息
         */
        @Data
        public static class FieldOrderInfo {
            @NotNull(message = "字段ID不能为空")
            private Long fieldId;
            
            @NotNull(message = "字段排序不能为空")
            private Integer fieldOrder;
        }
    }
}