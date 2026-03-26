package com.auraboot.framework.meta.view.schema;

import com.auraboot.framework.meta.view.schema.common.Action;
import com.auraboot.framework.meta.view.schema.common.CommonConfig;
import com.auraboot.framework.meta.view.schema.common.Meta;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 表格Schema Bean
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TableSchema extends CommonConfig {
    
    /**
     * 元数据
     */
    private Meta meta;

    /**
     * 列配置
     */
    private List<TableColumn> columns;
    
    /**
     * 操作配置
     */
    private List<Action> actions;
    /**
     * 批量操作
     */
    private List<Action> batchActions;

    private Map<String,Object> pagination;



}