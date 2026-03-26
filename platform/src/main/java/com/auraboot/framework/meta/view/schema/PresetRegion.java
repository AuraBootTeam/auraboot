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
public class PresetRegion extends AbstractPageRegion {
    
    private Map<String, Object> filters;
    private Map<String, Object> pagination;
    
    public PresetRegion() {
        super();
        setType("preset");
    }
}