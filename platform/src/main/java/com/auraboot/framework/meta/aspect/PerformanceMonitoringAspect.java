package com.auraboot.framework.meta.aspect;

import com.auraboot.framework.meta.monitor.MetaPerformanceMonitor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 性能监控切面
 * 
 * 自动记录Meta控制器方法的性能指标
 * 
 * @author AuraBoot Framework
 * @since 2.1.0
 */
@Slf4j
@Aspect
@Component
public class PerformanceMonitoringAspect {

    @Autowired
    private MetaPerformanceMonitor performanceMonitor;

    /**
     * 监控Meta控制器方法的性能
     */
    @Around("execution(* com.auraboot.framework.meta.controller..*(..))")
    public Object monitorApiPerformance(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getDeclaringTypeName() + "." + joinPoint.getSignature().getName();
        Instant startTime = Instant.now();
        boolean success = false;
        
        try {
            Object result = joinPoint.proceed();
            success = true;
            return result;
        } catch (Exception e) {
            success = false;
            throw e;
        } finally {
            Duration duration = Duration.between(startTime, Instant.now());
            performanceMonitor.recordApiRequest(methodName, duration, success);
        }
    }

    /**
     * 监控权限检查方法的性能
     */
    @Around("execution(* com.auraboot.framework.meta.security..*(..))")
    public Object monitorPermissionCheckPerformance(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getDeclaringTypeName() + "." + joinPoint.getSignature().getName();
        Instant startTime = Instant.now();
        boolean granted = false;
        
        try {
            Object result = joinPoint.proceed();
            // 假设返回true表示权限通过，false表示权限拒绝
            if (result instanceof Boolean) {
                granted = (Boolean) result;
            } else {
                granted = true; // 如果没有抛出异常，认为权限检查通过
            }
            return result;
        } catch (Exception e) {
            granted = false;
            throw e;
        } finally {
            Duration duration = Duration.between(startTime, Instant.now());
            performanceMonitor.recordPermissionCheck(methodName, duration, granted);
        }
    }

    /**
     * 监控查询执行方法的性能
     */
    @Around("execution(* com.auraboot.framework.meta.service.SecureQueryExecutor.*(..))")
    public Object monitorQueryExecutionPerformance(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        Instant startTime = Instant.now();
        boolean success = false;
        
        try {
            Object result = joinPoint.proceed();
            success = true;
            return result;
        } catch (Exception e) {
            success = false;
            throw e;
        } finally {
            Duration duration = Duration.between(startTime, Instant.now());
            performanceMonitor.recordQueryExecution(methodName, duration, success);
        }
    }


}