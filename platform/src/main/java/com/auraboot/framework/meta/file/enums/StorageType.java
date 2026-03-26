package com.auraboot.framework.meta.file.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 存储类型枚举
 * 
 * @author AuraBoot Team
 * @since 1.0.0
 */
@Getter
public enum StorageType {
    /**
     * 关系型数据库存储
     */
    RELATIONAL_DATABASE("relational_database", "关系型数据库存储"),
    
    /**
     * NoSQL数据库存储
     */
    NOSQL_DATABASE("nosql_database", "NoSQL数据库存储"),
    
    /**
     * 文件系统存储
     */
    FILE_SYSTEM("file_system", "文件系统存储"),
    
    /**
     * 内存存储
     */
    MEMORY("memory", "内存存储"),
    
    /**
     * 混合存储
     */
    HYBRID("hybrid", "混合存储");
    
    @EnumValue
    @JsonValue
    private final String code;
    private final String description;
    
    StorageType(String code, String description) {
        this.code = code;
        this.description = description;
    }
    
    /**
     * 根据代码获取存储类型
     * 
     * @param code 存储类型代码
     * @return 存储类型
     * @throws IllegalArgumentException 当代码不存在时抛出异常
     */
    public static StorageType fromCode(String code) {
        for (StorageType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown storage type code: " + code);
    }
}