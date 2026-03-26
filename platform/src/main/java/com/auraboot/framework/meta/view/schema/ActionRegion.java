package com.auraboot.framework.meta.view.schema;

import com.auraboot.framework.meta.view.schema.common.Action;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 页面区域Bean - 支持多态类型
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActionRegion extends AbstractPageRegion {
    
    private List<Action> actions;
    
    public ActionRegion() {
        super();
        setType("action");
    }
}