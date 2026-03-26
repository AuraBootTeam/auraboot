package com.auraboot.framework.meta.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 绑定关系版本历史DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class BindingVersionHistory {

    /**
     * 历史记录ID
     */
    private Long id;

    /**
     * 模型ID
     */
    private Long modelId;

    /**
     * 字段ID
     */
    private Long fieldId;

    /**
     * 版本号
     */
    private Integer version;

    /**
     * 操作类型
     */
    private String operationType;

    /**
     * 操作描述
     */
    private String description;

    /**
     * 操作时间
     */
    private LocalDateTime operationTime;

    /**
     * 操作用户
     */
    private String operatorId;

    /**
     * 变更内容
     */
    private Object changeContent;

    /**
     * 扩展信息
     */
    private Object extension;
}