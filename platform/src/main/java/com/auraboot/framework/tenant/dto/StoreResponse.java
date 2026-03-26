package com.auraboot.framework.tenant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 门店响应DTO
 * 用于返回门店信息
 */
@Data
public class StoreResponse {

    /**
     * 门店业务主键
     */
    @JsonProperty("pid")
    private String pid;

    /**
     * 门店名称
     */
    @JsonProperty("name")
    private String name;

    /**
     * 门店编码
     */
    @JsonProperty("code")
    private String code;

    /**
     * 门店类型
     */
    @JsonProperty("type")
    private String type;

    /**
     * 所属租户ID
     */
    @JsonProperty("tenantId")
    private Long tenantId;

    /**
     * 地址ID
     */
    @JsonProperty("addressId")
    private Long addressId;

    /**
     * 门店状态
     */
    @JsonProperty("status")
    private String status;

    /**
     * 开业日期
     */
    @JsonProperty("openDate")
    private LocalDateTime openDate;

    /**
     * 关闭日期
     */
    @JsonProperty("closeDate")
    private LocalDateTime closeDate;

    /**
     * 扩展信息
     */
    @JsonProperty("extension")
    private Map<String, Object> extension;

    /**
     * 创建时间
     */
    @JsonProperty("createdAt")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @JsonProperty("updatedAt")
    private LocalDateTime updatedAt;

    /**
     * 创建人
     */
    @JsonProperty("createdBy")
    private Long createdBy;

    /**
     * 更新人
     */
    @JsonProperty("updatedBy")
    private Long updatedBy;
}