package com.auraboot.framework.meta.view.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 页面区域Bean - 支持多态类型
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FilterRegion extends AbstractPageRegion {
    
    private List<FormField> fields;
    
    public FilterRegion() {
        super();
        setType("filters");
    }
}