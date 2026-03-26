package com.auraboot.framework.meta.dto;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储操作结果DTO
 * 用于封装存储操作的执行结果
 * 
 * @author AuraBoot Team
 * @since 1.0.0
 */
public class StorageOperationResult {
    
    /**
     * 操作是否成功
     */
    private boolean success;
    
    /**
     * 操作类型
     */
    private OperationType operationType;
    
    /**
     * 结果消息
     */
    private String message;
    
    /**
     * 错误代码
     */
    private String errorCode;
    
    /**
     * 操作开始时间
     */
    private LocalDateTime startTime;
    
    /**
     * 操作结束时间
     */
    private LocalDateTime endTime;
    
    /**
     * 影响的记录数
     */
    private long affectedRows;
    
    /**
     * 执行的SQL语句或操作语句
     */
    private List<String> executedStatements;
    
    /**
     * 操作详细信息
     */
    private Map<String, Object> details;
    
    /**
     * 异常信息
     */
    private Throwable exception;
    
    /**
     * 操作结果数据
     */
    private Object resultData;
    
    /**
     * 警告信息列表
     */
    private List<String> warnings;
    
    /**
     * 性能指标
     */
    private Map<String, Object> performanceMetrics;
    
    public StorageOperationResult() {
        this.executedStatements = new ArrayList<>();
        this.details = new HashMap<>();
        this.warnings = new ArrayList<>();
        this.performanceMetrics = new HashMap<>();
        this.startTime = LocalDateTime.now(ZoneOffset.UTC);
    }
    
    public StorageOperationResult(boolean success, String message) {
        this();
        this.success = success;
        this.message = message;
        this.endTime = LocalDateTime.now(ZoneOffset.UTC);
    }
    
    public StorageOperationResult(boolean success, OperationType operationType, String message) {
        this(success, message);
        this.operationType = operationType;
    }
    
    /**
     * 创建成功结果
     * 
     * @param operationType 操作类型
     * @param message 消息
     * @return 操作结果
     */
    public static StorageOperationResult success(OperationType operationType, String message) {
        return new StorageOperationResult(true, operationType, message);
    }
    
    /**
     * 创建成功结果（带影响行数）
     * 
     * @param operationType 操作类型
     * @param message 消息
     * @param affectedRows 影响行数
     * @return 操作结果
     */
    public static StorageOperationResult success(OperationType operationType, String message, long affectedRows) {
        StorageOperationResult result = success(operationType, message);
        result.setAffectedRows(affectedRows);
        return result;
    }
    
    /**
     * 创建失败结果
     * 
     * @param operationType 操作类型
     * @param message 错误消息
     * @return 操作结果
     */
    public static StorageOperationResult failure(OperationType operationType, String message) {
        return new StorageOperationResult(false, operationType, message);
    }
    
    /**
     * 创建失败结果（带异常）
     * 
     * @param operationType 操作类型
     * @param message 错误消息
     * @param exception 异常
     * @return 操作结果
     */
    public static StorageOperationResult failure(OperationType operationType, String message, Throwable exception) {
        StorageOperationResult result = failure(operationType, message);
        result.setException(exception);
        return result;
    }
    
    /**
     * 标记操作完成
     */
    public void markCompleted() {
        this.endTime = LocalDateTime.now(ZoneOffset.UTC);
    }
    
    /**
     * 获取执行耗时（毫秒）
     * 
     * @return 执行耗时
     */
    public long getExecutionTimeMs() {
        if (startTime == null || endTime == null) {
            return 0;
        }
        return java.time.Duration.between(startTime, endTime).toMillis();
    }
    
    /**
     * 添加执行语句
     * 
     * @param statement 执行语句
     */
    public void addExecutedStatement(String statement) {
        this.executedStatements.add(statement);
    }
    
    /**
     * 添加详细信息
     * 
     * @param key 键
     * @param value 值
     */
    public void addDetail(String key, Object value) {
        this.details.put(key, value);
    }
    
    /**
     * 添加警告信息
     * 
     * @param warning 警告信息
     */
    public void addWarning(String warning) {
        this.warnings.add(warning);
    }
    
    /**
     * 添加性能指标
     * 
     * @param metric 指标名称
     * @param value 指标值
     */
    public void addPerformanceMetric(String metric, Object value) {
        this.performanceMetrics.put(metric, value);
    }
    
    // Getters and Setters
    public boolean isSuccess() {
        return success;
    }
    
    public void setSuccess(boolean success) {
        this.success = success;
    }
    
    public OperationType getOperationType() {
        return operationType;
    }
    
    public void setOperationType(OperationType operationType) {
        this.operationType = operationType;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public String getErrorCode() {
        return errorCode;
    }
    
    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }
    
    public LocalDateTime getStartTime() {
        return startTime;
    }
    
    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }
    
    public LocalDateTime getEndTime() {
        return endTime;
    }
    
    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }
    
    public long getAffectedRows() {
        return affectedRows;
    }
    
    public void setAffectedRows(long affectedRows) {
        this.affectedRows = affectedRows;
    }
    
    public List<String> getExecutedStatements() {
        return executedStatements;
    }
    
    public void setExecutedStatements(List<String> executedStatements) {
        this.executedStatements = executedStatements;
    }
    
    public Map<String, Object> getDetails() {
        return details;
    }
    
    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }
    
    public Throwable getException() {
        return exception;
    }
    
    public void setException(Throwable exception) {
        this.exception = exception;
    }
    
    public Object getResultData() {
        return resultData;
    }
    
    public void setResultData(Object resultData) {
        this.resultData = resultData;
    }
    
    public List<String> getWarnings() {
        return warnings;
    }
    
    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }
    
    public Map<String, Object> getPerformanceMetrics() {
        return performanceMetrics;
    }
    
    public void setPerformanceMetrics(Map<String, Object> performanceMetrics) {
        this.performanceMetrics = performanceMetrics;
    }
    
    /**
     * 操作类型枚举
     */
    public enum OperationType {
        /**
         * 初始化存储
         */
        INITIALIZE_STORAGE("initialize_storage", "初始化存储"),
        
        /**
         * 更新存储结构
         */
        UPDATE_STORAGE("update_storage", "更新存储结构"),
        
        /**
         * 删除存储结构
         */
        DROP_STORAGE("drop_storage", "删除存储结构"),
        
        /**
         * 插入数据
         */
        INSERT("insert", "插入数据"),
        
        /**
         * 批量插入数据
         */
        BATCH_INSERT("batch_insert", "批量插入数据"),
        
        /**
         * 更新数据
         */
        UPDATE("update", "更新数据"),
        
        /**
         * 删除数据
         */
        DELETE("delete", "删除数据"),
        
        /**
         * 批量删除数据
         */
        BATCH_DELETE("batch_delete", "批量删除数据"),
        
        /**
         * 查询数据
         */
        QUERY("query", "查询数据"),
        
        /**
         * 验证存储
         */
        VALIDATE_STORAGE("validate_storage", "验证存储"),
        
        /**
         * 备份存储
         */
        BACKUP_STORAGE("backup_storage", "备份存储"),
        
        /**
         * 恢复存储
         */
        RESTORE_STORAGE("restore_storage", "恢复存储"),
        
        /**
         * 事务操作
         */
        TRANSACTION("transaction", "事务操作");
        
        private final String code;
        private final String description;
        
        OperationType(String code, String description) {
            this.code = code;
            this.description = description;
        }
        
        public String getCode() {
            return code;
        }
        
        public String getDescription() {
            return description;
        }
        
        public static OperationType fromCode(String code) {
            for (OperationType type : values()) {
                if (type.code.equals(code)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown operation type code: " + code);
        }
    }
}