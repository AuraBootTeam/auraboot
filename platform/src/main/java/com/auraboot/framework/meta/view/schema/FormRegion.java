package com.auraboot.framework.meta.view.schema;

import com.auraboot.framework.meta.view.schema.common.Action;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 页面区域Bean - 支持多态类型
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FormRegion extends  AbstractPageRegion{
    
    private List<FormSection> formSections;

    public FormRegion() {
        super();
        setType("form");
    }
}