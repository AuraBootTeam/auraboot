package com.auraboot.framework.meta.view.schema;

import com.auraboot.framework.meta.view.schema.common.CommonConfig;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/**
 * 表格列Bean
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TableColumn extends CommonConfig {
    
    /**
     * 列代码
     */
    private String code;

    
    /**
     * 国际化标题
     */
    private Map<String, String> label;

    
    /**
     * 是否可排序
     */
    private Boolean sortable;
    


}