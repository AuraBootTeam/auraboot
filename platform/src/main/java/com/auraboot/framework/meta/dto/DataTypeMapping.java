package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 数据类型映射
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class DataTypeMapping {
    
    /**
     * Java类型
     */
    private String javaType;
    
    /**
     * JDBC类型
     */
    private String jdbcType;
    
    /**
     * 数据库类型
     */
    private String dbType;
    
    /**
     * 长度
     */
    private Integer length;
    
    /**
     * 精度
     */
    private Integer precision;
    
    /**
     * 小数位数
     */
    private Integer scale;
    
    /**
     * 是否可为空
     */
    @Builder.Default
    private Boolean nullable = true;
}