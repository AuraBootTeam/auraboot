package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 关联查询配置
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class RelationQueryConfig {
    
    /**
     * 是否加载关联数据
     */
    @Builder.Default
    private Boolean load = true;
    
    /**
     * 加载的字段列表
     */
    private List<String> fields;
    
    /**
     * 查询条件
     */
    private List<QueryCondition> conditions;
    
    /**
     * 排序字段
     */
    private List<SortField> sortFields;
    
    /**
     * 限制数量
     */
    private Integer limit;
}