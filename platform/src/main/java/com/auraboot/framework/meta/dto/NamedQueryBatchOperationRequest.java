package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * 命名查询批量操作请求DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryBatchOperationRequest {

    /**
     * 操作类型
     */
    @NotNull(message = "操作类型不能为空")
    private String operationType; // ENABLE, DISABLE, DELETE, UPDATE, COPY, MOVE, EXPORT, VALIDATE

    /**
     * 查询ID列表
     */
    @NotEmpty(message = "查询ID列表不能为空")
    private List<Long> queryIds;

    /**
     * 操作参数
     */
    private Map<String, Object> operationParams;

    /**
     * 批量更新数据（用于UPDATE操作）
     */
    private JsonNode updateData;



    /**
     * 目标环境（用于COPY/MOVE操作）
     */
    private String targetEnv;

    /**
     * 是否继续处理错误
     */
    private Boolean continueOnError = true;

    /**
     * 最大错误数
     */
    private Integer maxErrors = 10;

    /**
     * 批次大小
     */
    private Integer batchSize = 100;

    /**
     * 是否异步执行
     */
    private Boolean asyncExecution = false;

    /**
     * 是否事务处理
     */
    private Boolean transactional = true;

    /**
     * 操作选项
     */
    private BatchOperationOptions options;

    /**
     * 操作备注
     */
    private String operationNotes;

    /**
     * 批量操作选项内部类
     */
    @Data
    public static class BatchOperationOptions {
        /**
         * 是否验证操作
         */
        private Boolean validateOperation = true;

        /**
         * 是否备份数据
         */
        private Boolean backupData = false;

        /**
         * 是否发送通知
         */
        private Boolean sendNotification = false;

        /**
         * 通知邮箱
         */
        private String notificationEmail;

        /**
         * 超时时间（秒）
         */
        private Integer timeoutSeconds = 300;

        /**
         * 重试次数
         */
        private Integer retryCount = 3;

        /**
         * 重试间隔（秒）
         */
        private Integer retryIntervalSeconds = 5;

        /**
         * 是否记录详细日志
         */
        private Boolean detailedLogging = true;

        /**
         * 自定义配置
         */
        private Map<String, Object> customConfig;
    }
}