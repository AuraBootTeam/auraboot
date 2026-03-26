package com.auraboot.framework.tenant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 门店查询请求DTO
 * 用于门店列表查询的条件参数
 */
@Data
public class StoreQueryRequest {

    /**
     * 门店名称（模糊查询）
     */
    @JsonProperty("name")
    private String name;

    /**
     * 门店编码（模糊查询）
     */
    @JsonProperty("code")
    private String code;

    /**
     * 门店类型
     */
    @JsonProperty("type")
    private String type;

    /**
     * 门店状态
     */
    @JsonProperty("status")
    private String status;

    /**
     * 地址ID
     */
    @JsonProperty("addressId")
    private Long addressId;

    /**
     * 关键词搜索（名称或编码）
     */
    @JsonProperty("keyword")
    private String keyword;
}