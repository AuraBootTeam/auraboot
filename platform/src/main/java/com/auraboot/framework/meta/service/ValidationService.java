package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.exception.ValidationException;
import java.util.Map;

/**
 * 数据验证服务（统一版本）
 * 职责：提供模型数据的验证功能，支持多种验证模式
 *
 * 合并了原EnhancedValidationService的功能
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
public interface ValidationService {

    /**
     * 验证模式枚举
     */
    enum ValidationMode {
        /** 普通模式：返回验证结果，不抛出异常 */
        NORMAL,
        /** 严格模式：验证失败时抛出异常 */
        STRICT,
        /** 租户隔离模式：额外验证租户上下文 */
        TENANT_ISOLATED
    }

    /**
     * 验证数据
     * @param modelDefinition 模型定义
     * @param data 待验证数据
     * @param context 验证上下文
     * @return 验证结果
     */
    ValidationResult validateData(ModelDefinition modelDefinition, Map<String, Object> data, ValidationContext context);

    /**
     * 验证单个字段
     * @param fieldDefinition 字段定义
     * @param value 字段值
     * @param context 验证上下文
     * @return 字段验证结果
     */
    FieldValidationResult validateField(FieldDefinition fieldDefinition, Object value, ValidationContext context);

    /**
     * 验证业务规则
     * @param modelDefinition 模型定义
     * @param data 数据
     * @param businessRules 业务规则
     * @return 验证结果
     */
    ValidationResult validateBusinessRules(ModelDefinition modelDefinition, Map<String, Object> data, 
                                         BusinessRuleSet businessRules);

    /**
     * 验证唯一性约束
     * @param modelDefinition 模型定义
     * @param data 数据
     * @param context 验证上下文
     * @return 验证结果
     */
    ValidationResult validateUniqueness(ModelDefinition modelDefinition, Map<String, Object> data, 
                                      ValidationContext context);

    /**
     * 验证关联关系
     * @param modelDefinition 模型定义
     * @param data 数据
     * @param context 验证上下文
     * @return 验证结果
     */
    ValidationResult validateRelations(ModelDefinition modelDefinition, Map<String, Object> data, 
                                     ValidationContext context);

    /**
     * 验证数据完整性
     * @param modelDefinition 模型定义
     * @param data 数据
     * @return 验证结果
     */
    ValidationResult validateDataIntegrity(ModelDefinition modelDefinition, Map<String, Object> data);

    // ==================== 增强验证方法（合并自EnhancedValidationService） ====================

    /**
     * 带验证模式的数据验证
     * @param modelDefinition 模型定义
     * @param data 待验证数据
     * @param context 验证上下文
     * @param mode 验证模式
     * @return 验证结果
     * @throws ValidationException 当mode为STRICT且验证失败时抛出
     */
    ValidationResult validateData(ModelDefinition modelDefinition, Map<String, Object> data,
                                 ValidationContext context, ValidationMode mode);

    /**
     * 验证并在失败时抛出异常（相当于STRICT模式）
     * @param modelDefinition 模型定义
     * @param data 待验证数据
     * @param context 验证上下文
     * @throws ValidationException 验证失败时抛出异常
     */
    default void validateAndThrow(ModelDefinition modelDefinition, Map<String, Object> data,
                                ValidationContext context) {
        ValidationResult result = validateData(modelDefinition, data, context, ValidationMode.STRICT);
        if (!result.getValid()) {
            throw new ValidationException("Validation failed: " + String.join(", ", result.getErrors()));
        }
    }

    /**
     * 验证租户隔离
     * @param data 数据
     * @throws ValidationException 租户上下文无效时抛出异常
     */
    void validateTenantIsolation(Map<String, Object> data);
}