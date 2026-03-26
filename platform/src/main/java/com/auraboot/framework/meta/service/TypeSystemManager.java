package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 类型系统管理器
 * 
 * 职责：
 * 1. 根据字段定义生成主键（支持UUID、Long、Integer等多种类型）
 * 2. 数据类型转换和验证
 * 3. 统一DDL定义与应用代码的数据类型映射
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Slf4j
@Service
public class TypeSystemManager {

    //fixme
    
    // 用于生成Long类型ID的计数器（简单实现，生产环境应使用分布式ID生成器）
    private final AtomicLong longIdCounter = new AtomicLong(System.currentTimeMillis());
    
    // 用于生成Integer类型ID的计数器
    private final AtomicLong intIdCounter = new AtomicLong(1);
    
    /**
     * 根据字段定义生成主键
     * 
     * @param pkField 主键字段定义
     * @return 生成的主键值
     */
    public Object generatePrimaryKey(FieldDefinition pkField) {
        if (pkField == null) {
            log.warn("Primary key field is null, using UUID as default");
            return com.auraboot.framework.common.util.UniqueIdGenerator.generate();
        }
        
        String dataType = pkField.getDataType();
        if (dataType == null || dataType.trim().isEmpty()) {
            log.warn("Primary key data type is null or empty, using UUID as default");
            return com.auraboot.framework.common.util.UniqueIdGenerator.generate();
        }
        
        String type = dataType.toLowerCase();
        
        switch (type) {
            case "uuid":
            case "string":
            case "varchar":
            case "text":
                // 生成UUID字符串（去掉连字符）
                return com.auraboot.framework.common.util.UniqueIdGenerator.generate();
                
            case "long":
            case "bigint":
                // 生成Long类型ID
                return generateLongId();
                
            case "integer":
            case "int":
                // 生成Integer类型ID
                return generateIntId();
                
            default:
                // 默认使用UUID字符串
                log.warn("Unknown primary key type: {}, using UUID as default", type);
                return com.auraboot.framework.common.util.UniqueIdGenerator.generate();
        }
    }
    
    /**
     * 生成Long类型ID
     * 
     * 注意：这是简单实现，生产环境应使用分布式ID生成器（如Snowflake）
     * 
     * @return Long类型ID
     */
    private Long generateLongId() {
        return longIdCounter.incrementAndGet();
    }
    
    /**
     * 生成Integer类型ID
     * 
     * @return Integer类型ID
     */
    private Integer generateIntId() {
        long value = intIdCounter.incrementAndGet();
        if (value > Integer.MAX_VALUE) {
            // 重置计数器
            intIdCounter.set(1);
            value = 1;
        }
        return (int) value;
    }
    
    /**
     * 数据类型转换
     * 
     * @param value 原始值
     * @param field 字段定义
     * @return 转换后的值
     * @throws MetaServiceException 如果转换失败
     */
    public Object convertValue(Object value, FieldDefinition field) {
        if (value == null) {
            return null;
        }

        if (field == null || field.getDataType() == null) {
            throw new MetaServiceException("Field definition or data type cannot be null");
        }
        
        String type = field.getDataType().toLowerCase();
        
        try {
            switch (type) {
                case "string":
                case "varchar":
                case "text":
                case "uuid":
                    return convertToString(value);
                    
                case "long":
                case "bigint":
                    return convertToLong(value);
                    
                case "integer":
                case "int":
                    return convertToInteger(value);
                    
                case "boolean":
                case "bool":
                    return convertToBoolean(value);
                    
                case "decimal":
                case "numeric":
                case "double":
                    return convertToDecimal(value);
                    
                case "date":
                    return convertToDate(value);
                    
                case "datetime":
                case "timestamp":
                    return convertToDateTime(value);
                    
                default:
                    // 未知类型，返回原值
                    log.debug("Unknown data type: {}, returning original value", type);
                    return value;
            }
        } catch (NumberFormatException e) {
            String errorMsg = String.format(
                "Failed to convert value '%s' to numeric type '%s' for field '%s': invalid number format",
                value, type, field.getCode()
            );
            log.error(errorMsg, e);
            throw new MetaServiceException(errorMsg, e);
        } catch (DateTimeParseException e) {
            String errorMsg = String.format(
                "Failed to convert value '%s' to date/time type '%s' for field '%s': invalid date format",
                value, type, field.getCode()
            );
            log.error(errorMsg, e);
            throw new MetaServiceException(errorMsg, e);
        } catch (IllegalArgumentException e) {
            String errorMsg = String.format(
                "Failed to convert value '%s' to type '%s' for field '%s': %s",
                value, type, field.getCode(), e.getMessage()
            );
            log.error(errorMsg, e);
            throw new MetaServiceException(errorMsg, e);
        }
    }
    
    /**
     * 转换为字符串
     */
    private String convertToString(Object value) {
        return value.toString();
    }
    
    /**
     * 转换为Long
     */
    private Long convertToLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(value.toString().trim());
    }
    
    /**
     * 转换为Integer
     */
    private Integer convertToInteger(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(value.toString().trim());
    }
    
    /**
     * 转换为Boolean
     */
    private Boolean convertToBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String str = value.toString().trim().toLowerCase();
        return "true".equals(str) || "1".equals(str) || "yes".equals(str);
    }
    
    /**
     * 转换为Decimal
     */
    private BigDecimal convertToDecimal(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        return new BigDecimal(value.toString().trim());
    }
    
    /**
     * 转换为Date
     */
    private LocalDate convertToDate(Object value) {
        if (value instanceof LocalDate) {
            return (LocalDate) value;
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).toLocalDate();
        }
        // 尝试解析字符串
        String str = value.toString().trim();
        return LocalDate.parse(str, DateTimeFormatter.ISO_LOCAL_DATE);
    }
    
    /**
     * 转换为DateTime
     */
    private LocalDateTime convertToDateTime(Object value) {
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        if (value instanceof LocalDate) {
            return ((LocalDate) value).atStartOfDay();
        }
        // 尝试解析字符串
        String str = value.toString().trim();
        return LocalDateTime.parse(str, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
    
    /**
     * 验证值是否符合字段类型
     * 
     * @param value 值
     * @param field 字段定义
     * @return true如果类型匹配
     */
    public boolean isValidType(Object value, FieldDefinition field) {
        if (value == null) {
            return true; // null值总是有效的
        }
        
        if (field == null || field.getDataType() == null) {
            return false;
        }
        
        try {
            convertValue(value, field);
            return true;
        } catch (MetaServiceException e) {
            log.debug("Type validation failed for field {}: {}", field.getCode(), e.getMessage());
            return false;
        }
    }
    
    /**
     * 获取Java类型对应的SQL类型
     * 
     * @param javaType Java类型名称
     * @return SQL类型
     */
    public String getSqlType(String javaType) {
        if (javaType == null) {
            return "VARCHAR(255)";
        }
        
        switch (javaType.toLowerCase()) {
            case "string":
            case "uuid":
                return "VARCHAR(255)";
                
            case "text":
                return "text";
                
            case "long":
            case "bigint":
                return "bigint";
                
            case "integer":
            case "int":
                return "integer";
                
            case "boolean":
            case "bool":
                return "boolean";
                
            case "decimal":
            case "numeric":
                return "DECIMAL(19,4)";
                
            case "double":
                return "DOUBLE PRECISION";
                
            case "date":
                return "date";
                
            case "datetime":
            case "timestamp":
                return "timestamp";
                
            default:
                log.warn("Unknown Java type: {}, using VARCHAR(255) as default", javaType);
                return "VARCHAR(255)";
        }
    }
    
    /**
     * 获取SQL类型对应的Java类型
     * 
     * @param sqlType SQL类型
     * @return Java类型
     */
    public String getJavaType(String sqlType) {
        if (sqlType == null) {
            return "string";
        }
        
        String type = sqlType.toLowerCase();

        if (type.startsWith("varchar") || type.startsWith("char")) {
            return "string";
        } else if (type.equals("text")) {
            return "text";
        } else if (type.equals("bigint")) {
            return "long";
        } else if (type.equals("integer") || type.equals("int")) {
            return "integer";
        } else if (type.equals("boolean") || type.equals("bool")) {
            return "boolean";
        } else if (type.startsWith("decimal") || type.startsWith("numeric")) {
            return "decimal";
        } else if (type.equals("double precision") || type.equals("double")) {
            return "double";
        } else if (type.equals("date")) {
            return "date";
        } else if (type.equals("timestamp") || type.startsWith("timestamp")) {
            return "datetime";
        } else {
            log.warn("Unknown SQL type: {}, using STRING as default", sqlType);
            return "string";
        }
    }
}
