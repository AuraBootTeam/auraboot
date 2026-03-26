package com.auraboot.framework.meta.view.schema;

import com.auraboot.framework.meta.view.schema.common.Event;
import com.auraboot.framework.meta.view.schema.common.Endpoint;
import com.auraboot.framework.meta.view.schema.common.Meta;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 页面Schema Bean
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageFacade {
    
    /**
     * 元数据
     */
    private Meta meta;

    
    /**
     * 端点配置
     */
    private Endpoint endpoint;
    
    /**
     * 区域列表
     */
    private List<AbstractPageRegion> regions;

    /**
     * 效果列表
     */
    private List<Event> events;
}