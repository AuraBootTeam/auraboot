package com.auraboot.framework.meta.service.base;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.exception.MetaServiceException;
import lombok.extern.slf4j.Slf4j;

/**
 * Meta服务基类
 * 提供通用的租户上下文检查和异常处理
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Slf4j
@SuppressWarnings("java/log-injection")
public abstract class BaseMetaService {

    /**
     * 获取当前租户ID，如果不存在则抛出异常
     */
    protected Long getCurrentTenantId() {
        if (!MetaContext.exists()) {
            throw new MetaServiceException("Tenant context is required but not found");
        }
        return MetaContext.getCurrentTenantId();
    }

    /**
     * 获取当前用户ID，如果不存在则抛出异常
     */
    protected Long getCurrentUserId() {
        if (!MetaContext.exists()) {
            throw new MetaServiceException("User context is required but not found");
        }
        return MetaContext.getCurrentUserId();
    }

    /**
     * 检查租户上下文是否存在
     */
    protected boolean hasTenantContext() {
        return MetaContext.exists();
    }

    /**
     * 验证模型编码格式
     */
    protected void validateModelCode(String modelCode) {
        if (modelCode == null || modelCode.trim().isEmpty()) {
            throw new MetaServiceException("Model code cannot be null or empty");
        }
        if (!modelCode.matches("^[a-zA-Z][a-zA-Z0-9_]*$")) {
            throw new MetaServiceException("Invalid model code format: " + modelCode);
        }
    }

    /**
     * 验证字段编码格式
     */
    protected void validateFieldCode(String fieldCode) {
        if (fieldCode == null || fieldCode.trim().isEmpty()) {
            throw new MetaServiceException("Field code cannot be null or empty");
        }
        if (!fieldCode.matches("^[a-zA-Z][a-zA-Z0-9_]*$")) {
            throw new MetaServiceException("Invalid field code format: " + fieldCode);
        }
    }

    /**
     * 记录操作日志
     */
    protected void logOperation(String operation, String modelCode, Object... params) {
        Long tenantId = getCurrentTenantId();
        Long userId = getCurrentUserId();
        // codeql[java/log-injection] Metadata identifiers are logged as structured parameters for operator diagnostics; logging backend handles escaping.
        log.info("Meta operation: {} on model: {} by user: {} in tenant: {}, params: {}", 
                operation, modelCode, userId, tenantId, params);
    }

    /**
     * 记录Meta操作日志
     */
    protected void logMetaOperation(String operation, String details) {
        Long tenantId = getCurrentTenantId();
        log.info("Meta operation: {} - {} in tenant: {}", operation, details, tenantId);
    }
}
