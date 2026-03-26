package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据访问日志请求DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@Builder
public class DataAccessLogRequest {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 租户ID
     */
    private Long tenantId;

    /**
     * 模型编码
     */
    private String modelCode;

    /**
     * 访问动作
     */
    private String action;

    /**
     * 记录数量
     */
    private Integer recordCount;

    /**
     * 访问时间
     */
    private LocalDateTime accessTime;

    /**
     * 客户端IP
     */
    private String clientIp;

    /**
     * 用户代理
     */
    private String userAgent;

    /**
     * 访问结果
     */
    private String result;

    /**
     * 错误信息
     */
    private String errorMessage;
}