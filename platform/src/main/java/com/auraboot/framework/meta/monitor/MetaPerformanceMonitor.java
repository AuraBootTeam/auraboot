package com.auraboot.framework.meta.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Meta平台性能监控组件
 * 
 * 提供API响应时间、权限检查耗时、缓存命中率等性能指标监控
 * 
 * @author AuraBoot Framework
 * @since 2.1.0
 */
@Slf4j
@Component
public class MetaPerformanceMonitor implements HealthIndicator {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private CacheManager cacheManager;

    // 性能计数器
    private final Counter apiRequestCounter;
    private final Counter permissionCheckCounter;
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;
    private final Counter queryExecutionCounter;
    private final Counter dslProjectionCounter;

    // 性能计时器
    private final Timer apiResponseTimer;
    private final Timer permissionCheckTimer;
    private final Timer queryExecutionTimer;

    // 性能统计
    private final Map<String, PerformanceStats> apiStats = new ConcurrentHashMap<>();
    private final Map<String, PerformanceStats> permissionStats = new ConcurrentHashMap<>();
    private final AtomicLong totalCacheRequests = new AtomicLong(0);
    private final AtomicLong totalCacheHits = new AtomicLong(0);

    public MetaPerformanceMonitor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        // 初始化计数器
        this.apiRequestCounter = Counter.builder("meta.api.requests")
                .description("Meta API请求总数")
                .register(meterRegistry);
                
        this.permissionCheckCounter = Counter.builder("meta.permission.checks")
                .description("权限检查总数")
                .register(meterRegistry);
                
        this.cacheHitCounter = Counter.builder("meta.cache.hits")
                .description("缓存命中总数")
                .register(meterRegistry);
                
        this.cacheMissCounter = Counter.builder("meta.cache.misses")
                .description("缓存未命中总数")
                .register(meterRegistry);
                
        this.queryExecutionCounter = Counter.builder("meta.query.executions")
                .description("查询执行总数")
                .register(meterRegistry);
                
        this.dslProjectionCounter = Counter.builder("meta.dsl.projections")
                .description("DSL投影总数")
                .register(meterRegistry);

        // 初始化计时器
        this.apiResponseTimer = Timer.builder("meta.api.response.time")
                .description("Meta API响应时间")
                .register(meterRegistry);
                
        this.permissionCheckTimer = Timer.builder("meta.permission.check.time")
                .description("权限检查耗时")
                .register(meterRegistry);
                
        this.queryExecutionTimer = Timer.builder("meta.query.execution.time")
                .description("查询执行耗时")
                .register(meterRegistry);
                

    }

    /**
     * 记录API请求
     */
    public void recordApiRequest(String endpoint, Duration duration, boolean success) {
        apiRequestCounter.increment();
        apiResponseTimer.record(duration);
        
        // 更新API统计
        apiStats.computeIfAbsent(endpoint, k -> new PerformanceStats())
                .record(duration, success);
        
        log.debug("API请求记录: endpoint={}, duration={}ms, success={}", 
                endpoint, duration.toMillis(), success);
    }

    /**
     * 记录权限检查
     */
    public void recordPermissionCheck(String permission, Duration duration, boolean granted) {
        permissionCheckCounter.increment();
        permissionCheckTimer.record(duration);
        
        // 更新权限检查统计
        permissionStats.computeIfAbsent(permission, k -> new PerformanceStats())
                .record(duration, granted);
        
        log.debug("权限检查记录: permission={}, duration={}ms, granted={}",
                permission, duration.toMillis(), granted);
    }

    /**
     * 记录缓存命中
     */
    public void recordCacheHit(String cacheName) {
        cacheHitCounter.increment();
        totalCacheRequests.incrementAndGet();
        totalCacheHits.incrementAndGet();
        
        log.debug("缓存命中: cacheName={}", cacheName);
    }

    /**
     * 记录缓存未命中
     */
    public void recordCacheMiss(String cacheName) {
        cacheMissCounter.increment();
        totalCacheRequests.incrementAndGet();
        
        log.debug("缓存未命中: cacheName={}", cacheName);
    }

    /**
     * 记录查询执行
     */
    public void recordQueryExecution(String queryType, Duration duration, boolean success) {
        queryExecutionCounter.increment();
        queryExecutionTimer.record(duration);
        
        log.debug("查询执行记录: queryType={}, duration={}ms, success={}", 
                queryType, duration.toMillis(), success);
    }


    /**
     * 获取缓存命中率
     */
    public double getCacheHitRate() {
        long totalRequests = totalCacheRequests.get();
        if (totalRequests == 0) {
            return 0.0;
        }
        return (double) totalCacheHits.get() / totalRequests;
    }

    /**
     * 获取API性能统计
     */
    public Map<String, PerformanceStats> getApiStats() {
        return new ConcurrentHashMap<>(apiStats);
    }

    /**
     * 获取权限检查性能统计
     */
    public Map<String, PerformanceStats> getPermissionStats() {
        return new ConcurrentHashMap<>(permissionStats);
    }

    /**
     * 获取性能摘要
     */
    public PerformanceSummary getPerformanceSummary() {
        PerformanceSummary summary = new PerformanceSummary();
        summary.setTotalApiRequests(apiRequestCounter.count());
        summary.setTotalPermissionChecks(permissionCheckCounter.count());
        summary.setTotalQueryExecutions(queryExecutionCounter.count());
        summary.setTotalDslProjections(dslProjectionCounter.count());
        summary.setCacheHitRate(getCacheHitRate());
        summary.setAverageApiResponseTime(apiResponseTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
        summary.setAveragePermissionCheckTime(permissionCheckTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
        summary.setAverageQueryExecutionTime(queryExecutionTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
        return summary;
    }

    /**
     * 重置统计数据
     */
    public void resetStats() {
        apiStats.clear();
        permissionStats.clear();
        totalCacheRequests.set(0);
        totalCacheHits.set(0);
        
        log.info("性能统计数据已重置");
    }

    /**
     * 健康检查
     */
    @Override
    public Health health() {
        try {
            PerformanceSummary summary = getPerformanceSummary();
            
            Health.Builder builder = Health.up();
            
            // 检查API响应时间
            if (summary.getAverageApiResponseTime() > 1000) { // 超过1秒
                builder.withDetail("warning", "API平均响应时间较高: " + summary.getAverageApiResponseTime() + "ms");
            }
            
            // 检查缓存命中率
            if (summary.getCacheHitRate() < 0.8) { // 低于80%
                builder.withDetail("warning", "缓存命中率较低: " + String.format("%.2f%%", summary.getCacheHitRate() * 100));
            }
            
            // 添加性能指标
            builder.withDetail("totalApiRequests", summary.getTotalApiRequests())
                   .withDetail("averageApiResponseTime", summary.getAverageApiResponseTime() + "ms")
                   .withDetail("cacheHitRate", String.format("%.2f%%", summary.getCacheHitRate() * 100))
                   .withDetail("totalPermissionChecks", summary.getTotalPermissionChecks())
                   .withDetail("averagePermissionCheckTime", summary.getAveragePermissionCheckTime() + "ms");
            
            return builder.build();
            
        } catch (Exception e) {
            log.error("性能监控健康检查失败", e);
            return Health.down()
                    .withDetail("error", "性能监控组件异常: " + e.getMessage())
                    .build();
        }
    }

    // ==================== 内部类定义 ====================

    /**
     * 性能统计数据
     */
    public static class PerformanceStats {
        private final AtomicLong totalCount = new AtomicLong(0);
        private final AtomicLong successCount = new AtomicLong(0);
        private final AtomicLong totalDuration = new AtomicLong(0);
        private volatile long minDuration = Long.MAX_VALUE;
        private volatile long maxDuration = Long.MIN_VALUE;

        public void record(Duration duration, boolean success) {
            totalCount.incrementAndGet();
            if (success) {
                successCount.incrementAndGet();
            }
            
            long durationMs = duration.toMillis();
            totalDuration.addAndGet(durationMs);
            
            // 更新最小值和最大值
            synchronized (this) {
                if (durationMs < minDuration) {
                    minDuration = durationMs;
                }
                if (durationMs > maxDuration) {
                    maxDuration = durationMs;
                }
            }
        }

        public long getTotalCount() { return totalCount.get(); }
        public long getSuccessCount() { return successCount.get(); }
        public double getSuccessRate() { 
            long total = totalCount.get();
            return total == 0 ? 0.0 : (double) successCount.get() / total; 
        }
        public double getAverageDuration() { 
            long total = totalCount.get();
            return total == 0 ? 0.0 : (double) totalDuration.get() / total; 
        }
        public long getMinDuration() { return minDuration == Long.MAX_VALUE ? 0 : minDuration; }
        public long getMaxDuration() { return maxDuration == Long.MIN_VALUE ? 0 : maxDuration; }
    }

    /**
     * 性能摘要
     */
    public static class PerformanceSummary {
        private double totalApiRequests;
        private double totalPermissionChecks;
        private double totalQueryExecutions;
        private double totalDslProjections;
        private double cacheHitRate;
        private double averageApiResponseTime;
        private double averagePermissionCheckTime;
        private double averageQueryExecutionTime;
        private double averageDslProjectionTime;

        // Getters and Setters
        public double getTotalApiRequests() { return totalApiRequests; }
        public void setTotalApiRequests(double totalApiRequests) { this.totalApiRequests = totalApiRequests; }
        public double getTotalPermissionChecks() { return totalPermissionChecks; }
        public void setTotalPermissionChecks(double totalPermissionChecks) { this.totalPermissionChecks = totalPermissionChecks; }
        public double getTotalQueryExecutions() { return totalQueryExecutions; }
        public void setTotalQueryExecutions(double totalQueryExecutions) { this.totalQueryExecutions = totalQueryExecutions; }
        public double getTotalDslProjections() { return totalDslProjections; }
        public void setTotalDslProjections(double totalDslProjections) { this.totalDslProjections = totalDslProjections; }
        public double getCacheHitRate() { return cacheHitRate; }
        public void setCacheHitRate(double cacheHitRate) { this.cacheHitRate = cacheHitRate; }
        public double getAverageApiResponseTime() { return averageApiResponseTime; }
        public void setAverageApiResponseTime(double averageApiResponseTime) { this.averageApiResponseTime = averageApiResponseTime; }
        public double getAveragePermissionCheckTime() { return averagePermissionCheckTime; }
        public void setAveragePermissionCheckTime(double averagePermissionCheckTime) { this.averagePermissionCheckTime = averagePermissionCheckTime; }
        public double getAverageQueryExecutionTime() { return averageQueryExecutionTime; }
        public void setAverageQueryExecutionTime(double averageQueryExecutionTime) { this.averageQueryExecutionTime = averageQueryExecutionTime; }
        public double getAverageDslProjectionTime() { return averageDslProjectionTime; }
        public void setAverageDslProjectionTime(double averageDslProjectionTime) { this.averageDslProjectionTime = averageDslProjectionTime; }
    }
}