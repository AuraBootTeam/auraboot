package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 表信息
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class TableInfo {
    
    /**
     * 表名
     */
    private String tableName;
    
    /**
     * 表注释
     */
    private String comment;
    
    /**
     * 表引擎
     */
    private String engine;
    
    /**
     * 字符集
     */
    private String charset;
    
    /**
     * 排序规则
     */
    private String collation;
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
    
    /**
     * 表大小（字节）
     */
    private Long tableSize;
    
    /**
     * 行数
     */
    private Long rowCount;
    
    /**
     * 字段列表
     */
    private List<ColumnInfo> columns;
    
    /**
     * 索引列表
     */
    private List<IndexInfo> indexes;
    
    @Data
    @Builder
    public static class ColumnInfo {
        private String columnName;
        private String dataType;
        private Boolean nullable;
        private String defaultValue;
        private String comment;
        private Boolean primaryKey;
        private Boolean autoIncrement;
    }
}