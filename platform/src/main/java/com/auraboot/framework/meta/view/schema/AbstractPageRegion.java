package com.auraboot.framework.meta.view.schema;

import com.auraboot.framework.meta.view.schema.common.Action;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonProperty;
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
public abstract class AbstractPageRegion implements PageRegion {
    /**
     * 区域类型
     */
    protected String type;

    /**
     * 引用配置
     */
    protected Map<String, Object> ref;

    /**
     * 属性配置
     */
    protected Map<String, Object> props;

    @Override
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}