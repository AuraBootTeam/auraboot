package com.auraboot.framework.meta.dto;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储查询结果DTO
 * 用于封装存储查询的结果数据
 * 
 * @author AuraBoot Team
 * @since 1.0.0
 */
public class StorageQueryResult {
    
    /**
     * 查询是否成功
     */
    private boolean success;
    
    /**
     * 结果消息
     */
    private String message;
    
    /**
     * 错误代码
     */
    private String errorCode;
    
    /**
     * 查询开始时间
     */
    private LocalDateTime startTime;
    
    /**
     * 查询结束时间
     */
    private LocalDateTime endTime;
    
    /**
     * 查询结果数据列表
     */
    private List<Map<String, Object>> data;
    
    /**
     * 单条查询结果数据
     */
    private Map<String, Object> singleData;
    
    /**
     * 总记录数
     */
    private long totalCount;
    
    /**
     * 返回记录数
     */
    private int returnedCount;
    
    /**
     * 执行的查询语句
     */
    private String executedQuery;
    
    /**
     * 查询参数
     */
    private Map<String, Object> queryParameters;
    
    /**
     * 字段元数据信息
     */
    private List<FieldMetadata> fieldMetadata;
    
    /**
     * 查询统计信息
     */
    private QueryStatistics statistics;
    
    /**
     * 警告信息列表
     */
    private List<String> warnings;
    
    /**
     * 异常信息
     */
    private Throwable exception;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> extensions;
    
    public StorageQueryResult() {
        this.data = new ArrayList<>();
        this.singleData = new HashMap<>();
        this.queryParameters = new HashMap<>();
        this.fieldMetadata = new ArrayList<>();
        this.warnings = new ArrayList<>();
        this.extensions = new HashMap<>();
        this.startTime = LocalDateTime.now(ZoneOffset.UTC);
    }
    
    public StorageQueryResult(boolean success, String message) {
        this();
        this.success = success;
        this.message = message;
        this.endTime = LocalDateTime.now(ZoneOffset.UTC);
    }
    
    /**
     * 创建成功结果
     * 
     * @param data 结果数据
     * @return 查询结果
     */
    public static StorageQueryResult success(List<Map<String, Object>> data) {
        StorageQueryResult result = new StorageQueryResult(true, "查询成功");
        result.setData(data);
        result.setReturnedCount(data.size());
        return result;
    }
    
    /**
     * 创建单条数据成功结果
     * 
     * @param singleData 单条数据
     * @return 查询结果
     */
    public static StorageQueryResult success(Map<String, Object> singleData) {
        StorageQueryResult result = new StorageQueryResult(true, "查询成功");
        result.setSingleData(singleData);
        result.setReturnedCount(singleData != null ? 1 : 0);
        return result;
    }
    
    /**
     * 创建失败结果
     * 
     * @param message 错误消息
     * @return 查询结果
     */
    public static StorageQueryResult failure(String message) {
        return new StorageQueryResult(false, message);
    }
    
    /**
     * 创建失败结果（带异常）
     * 
     * @param message 错误消息
     * @param exception 异常
     * @return 查询结果
     */
    public static StorageQueryResult failure(String message, Throwable exception) {
        StorageQueryResult result = failure(message);
        result.setException(exception);
        return result;
    }
    
    /**
     * 标记查询完成
     */
    public void markCompleted() {
        this.endTime = LocalDateTime.now(ZoneOffset.UTC);
    }
    
    /**
     * 获取查询耗时（毫秒）
     * 
     * @return 查询耗时
     */
    public long getQueryTimeMs() {
        if (startTime == null || endTime == null) {
            return 0;
        }
        return endTime.toInstant(ZoneOffset.UTC).toEpochMilli() - startTime.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
    
    /**
     * 添加字段元数据
     * 
     * @param fieldName 字段名
     * @param fieldType 字段类型
     * @param nullable 是否可空
     */
    public void addFieldMetadata(String fieldName, String fieldType, boolean nullable) {
        this.fieldMetadata.add(new FieldMetadata(fieldName, fieldType, nullable));
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
     * 添加扩展属性
     * 
     * @param key 键
     * @param value 值
     */
    public void addExtension(String key, Object value) {
        this.extensions.put(key, value);
    }
    
    /**
     * 检查是否有数据
     * 
     * @return 是否有数据
     */
    public boolean hasData() {
        return (data != null && !data.isEmpty()) || (singleData != null && !singleData.isEmpty());
    }
    
    /**
     * 获取第一条数据
     * 
     * @return 第一条数据
     */
    public Map<String, Object> getFirstData() {
        if (singleData != null && !singleData.isEmpty()) {
            return singleData;
        }
        if (data != null && !data.isEmpty()) {
            return data.get(0);
        }
        return null;
    }
    
    // Getters and Setters
    public boolean isSuccess() {
        return success;
    }
    
    public void setSuccess(boolean success) {
        this.success = success;
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
    
    public List<Map<String, Object>> getData() {
        return data;
    }
    
    public void setData(List<Map<String, Object>> data) {
        this.data = data;
    }
    
    public Map<String, Object> getSingleData() {
        return singleData;
    }
    
    public void setSingleData(Map<String, Object> singleData) {
        this.singleData = singleData;
    }
    
    public long getTotalCount() {
        return totalCount;
    }
    
    public void setTotalCount(long totalCount) {
        this.totalCount = totalCount;
    }
    
    public int getReturnedCount() {
        return returnedCount;
    }
    
    public void setReturnedCount(int returnedCount) {
        this.returnedCount = returnedCount;
    }
    
    public String getExecutedQuery() {
        return executedQuery;
    }
    
    public void setExecutedQuery(String executedQuery) {
        this.executedQuery = executedQuery;
    }
    
    public Map<String, Object> getQueryParameters() {
        return queryParameters;
    }
    
    public void setQueryParameters(Map<String, Object> queryParameters) {
        this.queryParameters = queryParameters;
    }
    
    public List<FieldMetadata> getFieldMetadata() {
        return fieldMetadata;
    }
    
    public void setFieldMetadata(List<FieldMetadata> fieldMetadata) {
        this.fieldMetadata = fieldMetadata;
    }
    
    public QueryStatistics getStatistics() {
        return statistics;
    }
    
    public void setStatistics(QueryStatistics statistics) {
        this.statistics = statistics;
    }
    
    public List<String> getWarnings() {
        return warnings;
    }
    
    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }
    
    public Throwable getException() {
        return exception;
    }
    
    public void setException(Throwable exception) {
        this.exception = exception;
    }
    
    public Map<String, Object> getExtensions() {
        return extensions;
    }
    
    public void setExtensions(Map<String, Object> extensions) {
        this.extensions = extensions;
    }
    
    /**
     * 字段元数据
     */
    public static class FieldMetadata {
        private String fieldName;
        private String fieldType;
        private boolean nullable;
        private Integer length;
        private Integer precision;
        private Integer scale;
        private String comment;
        
        public FieldMetadata() {}
        
        public FieldMetadata(String fieldName, String fieldType, boolean nullable) {
            this.fieldName = fieldName;
            this.fieldType = fieldType;
            this.nullable = nullable;
        }
        
        // Getters and Setters
        public String getFieldName() {
            return fieldName;
        }
        
        public void setFieldName(String fieldName) {
            this.fieldName = fieldName;
        }
        
        public String getFieldType() {
            return fieldType;
        }
        
        public void setFieldType(String fieldType) {
            this.fieldType = fieldType;
        }
        
        public boolean isNullable() {
            return nullable;
        }
        
        public void setNullable(boolean nullable) {
            this.nullable = nullable;
        }
        
        public Integer getLength() {
            return length;
        }
        
        public void setLength(Integer length) {
            this.length = length;
        }
        
        public Integer getPrecision() {
            return precision;
        }
        
        public void setPrecision(Integer precision) {
            this.precision = precision;
        }
        
        public Integer getScale() {
            return scale;
        }
        
        public void setScale(Integer scale) {
            this.scale = scale;
        }
        
        public String getComment() {
            return comment;
        }
        
        public void setComment(String comment) {
            this.comment = comment;
        }
    }
    
    /**
     * 查询统计信息
     */
    public static class QueryStatistics {
        private long executionTimeMs;
        private long rowsExamined;
        private long rowsReturned;
        private boolean indexUsed;
        private String executionPlan;
        private Map<String, Object> performanceMetrics;
        
        public QueryStatistics() {
            this.performanceMetrics = new HashMap<>();
        }
        
        // Getters and Setters
        public long getExecutionTimeMs() {
            return executionTimeMs;
        }
        
        public void setExecutionTimeMs(long executionTimeMs) {
            this.executionTimeMs = executionTimeMs;
        }
        
        public long getRowsExamined() {
            return rowsExamined;
        }
        
        public void setRowsExamined(long rowsExamined) {
            this.rowsExamined = rowsExamined;
        }
        
        public long getRowsReturned() {
            return rowsReturned;
        }
        
        public void setRowsReturned(long rowsReturned) {
            this.rowsReturned = rowsReturned;
        }
        
        public boolean isIndexUsed() {
            return indexUsed;
        }
        
        public void setIndexUsed(boolean indexUsed) {
            this.indexUsed = indexUsed;
        }
        
        public String getExecutionPlan() {
            return executionPlan;
        }
        
        public void setExecutionPlan(String executionPlan) {
            this.executionPlan = executionPlan;
        }
        
        public Map<String, Object> getPerformanceMetrics() {
            return performanceMetrics;
        }
        
        public void setPerformanceMetrics(Map<String, Object> performanceMetrics) {
            this.performanceMetrics = performanceMetrics;
        }
    }
}