package com.auraboot.framework.meta.dto;

import lombok.Data;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 批量字段绑定请求DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class FieldBindingBatchRequest {

    /**
     * 模型ID
     */
    @NotNull(message = "模型ID不能为空")
    private Long modelId;

    /**
     * 字段绑定请求列表
     */
    @NotEmpty(message = "字段绑定列表不能为空")
    private List<FieldBindingItem> bindings;

    /**
     * 是否清空现有绑定
     */
    private Boolean clearExisting;

    /**
     * 是否强制绑定（忽略兼容性检查）
     */
    private Boolean forceBinding;

    /**
     * 批量操作模式
     */
    private BatchMode batchMode;

    /**
     * 扩展信息
     */
    private Object extension;

    /**
     * 构造函数
     */
    public FieldBindingBatchRequest() {
        this.clearExisting = false;
        this.forceBinding = false;
        this.batchMode = BatchMode.APPEND;
    }

    /**
     * 字段绑定项
     */
    @Data
    public static class FieldBindingItem {
        /**
         * 字段ID
         */
        @NotNull(message = "字段ID不能为空")
        private Long fieldId;

        /**
         * 字段排序
         */
        private Integer fieldOrder;

        /**
         * 是否必填
         */
        private Boolean required;

        /**
         * 是否只读
         */
        private Boolean readonly;

        /**
         * 是否可见
         */
        private Boolean visible;

        /**
         * 绑定配置
         */
        private Object bindingConfig;

        /**
         * 构造函数
         */
        public FieldBindingItem() {
            this.required = false;
            this.readonly = false;
            this.visible = true;
        }

        /**
         * 构造函数
         */
        public FieldBindingItem(Long fieldId, Integer fieldOrder) {
            this();
            this.fieldId = fieldId;
            this.fieldOrder = fieldOrder;
        }
    }

    /**
     * 批量操作模式
     */
    public enum BatchMode {
        /**
         * 追加模式 - 在现有绑定基础上添加新绑定
         */
        APPEND,

        /**
         * 替换模式 - 清空现有绑定后添加新绑定
         */
        REPLACE,

        /**
         * 合并模式 - 合并现有绑定和新绑定
         */
        MERGE
    }
}