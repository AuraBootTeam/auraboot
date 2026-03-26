package com.auraboot.framework.meta.view.schema.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 通用效果Bean - 用于表单和页面效果
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Event {
    
    /**
     * 触发事件
     */
    private String on;

    /**
     * 条件（可选）
     */
    @JsonProperty("if")
    private String when;
    
    /**
     * 执行动作 - 映射JSON中的do字段
     */
    @JsonProperty("do")
    private List<EventBehavior> behaviors;


}