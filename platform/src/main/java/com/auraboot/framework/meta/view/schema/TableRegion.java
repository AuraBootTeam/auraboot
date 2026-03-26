package com.auraboot.framework.meta.view.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/**
 * 页面区域Bean - 支持多态类型
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TableRegion extends AbstractPageRegion {
    
    private Map<String, Object> layout;
    private Map<String, Object> style;
    private Map<String, Object> pagination;
    
    public TableRegion() {
        super();
        setType("table");
    }
}