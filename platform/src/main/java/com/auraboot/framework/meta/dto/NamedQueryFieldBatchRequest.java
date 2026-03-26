package com.auraboot.framework.meta.dto;

import lombok.Data;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 命名查询字段批量请求DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryFieldBatchRequest {

    /**
     * 操作类型
     */
    @NotNull(message = "操作类型不能为空")
    private String operationType; // SET, ADD, UPDATE, DELETE

    /**
     * 字段列表
     */
    @Valid
    @NotEmpty(message = "字段列表不能为空")
    private List<NamedQueryFieldRequest> fields;

    /**
     * 是否清空现有字段
     */
    private Boolean clearExisting = false;

    /**
     * 是否验证字段
     */
    private Boolean validateFields = true;

    /**
     * 是否跳过重复字段
     */
    private Boolean skipDuplicates = true;

    /**
     * Source tag for the fields (PLUGIN or USER).
     * When set with clearExisting=true, only fields with matching source are cleared.
     * New fields will be tagged with this source value.
     */
    private String source;

    /**
     * 批量操作选项
     */
    private BatchOperationOptions options;

    /**
     * 操作备注
     */
    private String notes;

    /**
     * 批量操作选项内部类
     */
    @Data
    public static class BatchOperationOptions {
        /**
         * 是否继续处理错误
         */
        private Boolean continueOnError = true;

        /**
         * 最大错误数
         */
        private Integer maxErrors = 10;

        /**
         * 是否事务处理
         */
        private Boolean transactional = true;

        /**
         * 批次大小
         */
        private Integer batchSize = 100;
    }
}