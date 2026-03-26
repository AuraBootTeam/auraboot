package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;

/**
 * 命名查询测试请求DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryTestRequest {

    /**
     * 测试参数
     */
    private Map<String, Object> parameters;

    /**
     * WHERE条件
     */
    private JsonNode whereConditions;

    /**
     * 排序条件
     */
    private JsonNode orderConditions;

    /**
     * 分页页码
     */
    @Min(value = 1, message = "页码必须大于0")
    private Integer page = 1;

    /**
     * 分页大小
     */
    @Min(value = 1, message = "页大小必须大于0")
    @Max(value = 1000, message = "页大小不能超过1000")
    private Integer size = 20;

    /**
     * 是否只验证语法
     */
    private Boolean syntaxOnly = false;

    /**
     * 是否执行查询
     */
    private Boolean executeQuery = true;

    /**
     * 超时时间（秒）
     */
    @Min(value = 1, message = "超时时间必须大于0")
    @Max(value = 300, message = "超时时间不能超过300秒")
    private Integer timeoutSeconds = 30;

    /**
     * 测试环境
     */
    private String testEnvironment = "test";

    /**
     * 是否记录执行日志
     */
    private Boolean logExecution = true;

    /**
     * 测试备注
     */
    private String testNotes;
}