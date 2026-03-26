package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;

/**
 * 命名查询性能分析请求DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryPerformanceRequest {

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
     * 分析类型
     */
    private String analysisType = "full"; // FULL, QUICK, DEEP

    /**
     * 测试数据量级别
     */
    private String dataVolumeLevel = "normal"; // SMALL, NORMAL, LARGE, HUGE

    /**
     * 并发测试线程数
     */
    @Min(value = 1, message = "并发线程数必须大于0")
    @Max(value = 100, message = "并发线程数不能超过100")
    private Integer concurrentThreads = 1;

    /**
     * 测试执行次数
     */
    @Min(value = 1, message = "执行次数必须大于0")
    @Max(value = 1000, message = "执行次数不能超过1000")
    private Integer executionCount = 10;

    /**
     * 预热次数
     */
    @Min(value = 0, message = "预热次数不能小于0")
    private Integer warmupCount = 3;

    /**
     * 超时时间（秒）
     */
    @Min(value = 1, message = "超时时间必须大于0")
    @Max(value = 600, message = "超时时间不能超过600秒")
    private Integer timeoutSeconds = 60;

    /**
     * 是否分析执行计划
     */
    private Boolean analyzeExecutionPlan = true;

    /**
     * 是否分析索引使用
     */
    private Boolean analyzeIndexUsage = true;

    /**
     * 是否分析资源消耗
     */
    private Boolean analyzeResourceUsage = true;

    /**
     * 是否生成优化建议
     */
    private Boolean generateOptimizationSuggestions = true;

    /**
     * 分析环境
     */
    private String analysisEnvironment = "test";

    /**
     * 分析备注
     */
    private String analysisNotes;
}