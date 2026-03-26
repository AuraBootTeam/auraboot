package com.auraboot.framework.meta.controller;

import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.monitor.MetaPerformanceMonitor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Meta性能监控控制器
 * 
 * 提供性能指标查询和监控管理接口
 * 
 * @author AuraBoot Framework
 * @since 2.1.0
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/performance")
@Tag(name = "Meta性能监控", description = "Meta平台性能指标监控接口")
public class MetaPerformanceController {

    @Autowired
    private MetaPerformanceMonitor performanceMonitor;

    /**
     * 获取性能摘要
     */
    @GetMapping("/summary")
    @Operation(summary = "获取性能摘要", description = "获取Meta平台的整体性能指标摘要")
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<MetaPerformanceMonitor.PerformanceSummary> getPerformanceSummary() {
        log.debug("获取性能摘要");
        
        MetaPerformanceMonitor.PerformanceSummary summary = performanceMonitor.getPerformanceSummary();
        return ApiResponse.success(summary);
    }

    /**
     * 获取API性能统计
     */
    @GetMapping("/api-stats")
    @Operation(summary = "获取API性能统计", description = "获取各个API端点的详细性能统计")
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<Map<String, MetaPerformanceMonitor.PerformanceStats>> getApiStats() {
        log.debug("获取API性能统计");
        
        Map<String, MetaPerformanceMonitor.PerformanceStats> stats = performanceMonitor.getApiStats();
        return ApiResponse.success(stats);
    }

    /**
     * 获取权限检查性能统计
     */
    @GetMapping("/permission-stats")
    @Operation(summary = "获取权限检查性能统计", description = "获取各个权限检查的详细性能统计")
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<Map<String, MetaPerformanceMonitor.PerformanceStats>> getPermissionStats() {
        log.debug("获取权限检查性能统计");
        
        Map<String, MetaPerformanceMonitor.PerformanceStats> stats = performanceMonitor.getPermissionStats();
        return ApiResponse.success(stats);
    }

    /**
     * 获取缓存命中率
     */
    @GetMapping("/cache-hit-rate")
    @Operation(summary = "获取缓存命中率", description = "获取当前的缓存命中率")
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<Double> getCacheHitRate() {
        log.debug("获取缓存命中率");
        
        double hitRate = performanceMonitor.getCacheHitRate();
        return ApiResponse.success(hitRate);
    }

    /**
     * 获取健康状态
     */
    @GetMapping("/health")
    @Operation(summary = "获取性能健康状态", description = "获取Meta平台的性能健康状态")
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<Health> getHealth() {
        log.debug("获取性能健康状态");
        
        Health health = performanceMonitor.health();
        return ApiResponse.success(health);
    }

    /**
     * 重置性能统计
     */
    @PostMapping("/reset")
    @Operation(summary = "重置性能统计", description = "重置所有性能统计数据")
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ApiResponse<Void> resetStats() {
        log.info("重置性能统计");
        
        performanceMonitor.resetStats();
        return ApiResponse.success();
    }
}