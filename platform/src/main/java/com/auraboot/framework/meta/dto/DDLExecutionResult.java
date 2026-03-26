package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * DDL执行结果DTO
 * 用于封装DDL语句执行的结果信息
 * 
 * @author AuraBoot Team
 * @since 1.0.0
 */
@Data
public class DDLExecutionResult {
    
    /**
     * 执行是否成功
     */
    private Boolean success;
    
    /**
     * 执行的DDL语句
     */
    private String ddlStatement;
    
    /**
     * 执行开始时间
     */
    private LocalDateTime startTime;
    
    /**
     * 执行结束时间
     */
    private LocalDateTime endTime;
    
    /**
     * 执行耗时（毫秒）
     */
    private Long executionTime;
    
    /**
     * 影响的行数
     */
    private Integer affectedRows;
    
    /**
     * 错误消息
     */
    private String errorMessage;
    
    /**
     * 错误代码
     */
    private String errorCode;
    
    /**
     * 异常堆栈信息
     */
    private String stackTrace;
    
    /**
     * 执行上下文信息
     */
    private String context;
    
    /**
     * 警告信息列表
     */
    private List<String> warnings;
    
    /**
     * 创建成功结果
     * 
     * @param ddlStatement DDL语句
     * @param executionTime 执行时间
     * @param affectedRows 影响行数
     * @return 执行结果
     */
    public static DDLExecutionResult success(String ddlStatement, Long executionTime, Integer affectedRows) {
        DDLExecutionResult result = new DDLExecutionResult();
        result.setSuccess(true);
        result.setDdlStatement(ddlStatement);
        result.setExecutionTime(executionTime);
        result.setAffectedRows(affectedRows);
        result.setEndTime(LocalDateTime.now(ZoneOffset.UTC));
        result.setStartTime(LocalDateTime.now(ZoneOffset.UTC).minusNanos(executionTime * 1_000_000));
        return result;
    }
    
    /**
     * 创建失败结果
     * 
     * @param ddlStatement DDL语句
     * @param errorMessage 错误消息
     * @param errorCode 错误代码
     * @return 执行结果
     */
    public static DDLExecutionResult failure(String ddlStatement, String errorMessage, String errorCode) {
        DDLExecutionResult result = new DDLExecutionResult();
        result.setSuccess(false);
        result.setDdlStatement(ddlStatement);
        result.setErrorMessage(errorMessage);
        result.setErrorCode(errorCode);
        result.setEndTime(LocalDateTime.now(ZoneOffset.UTC));
        return result;
    }
    
    /**
     * 创建失败结果（带异常信息）
     * 
     * @param ddlStatement DDL语句
     * @param exception 异常
     * @return 执行结果
     */
    public static DDLExecutionResult failure(String ddlStatement, Exception exception) {
        DDLExecutionResult result = new DDLExecutionResult();
        result.setSuccess(false);
        result.setDdlStatement(ddlStatement);
        result.setErrorMessage(exception.getMessage());
        result.setStackTrace(getStackTrace(exception));
        result.setEndTime(LocalDateTime.now(ZoneOffset.UTC));
        return result;
    }
    
    /**
     * 获取异常堆栈信息
     * 
     * @param exception 异常
     * @return 堆栈信息字符串
     */
    private static String getStackTrace(Exception exception) {
        StringBuilder sb = new StringBuilder();
        sb.append(exception.getClass().getName()).append(": ").append(exception.getMessage()).append("\n");
        for (StackTraceElement element : exception.getStackTrace()) {
            sb.append("\tat ").append(element.toString()).append("\n");
        }
        return sb.toString();
    }
}