package com.auraboot.framework.tenant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 门店创建请求DTO
 * 用于创建新的门店
 */
@Data
public class StoreCreateRequest {

    /**
     * 门店名称
     * 必填，长度限制
     */
    @NotBlank(message = "门店名称不能为空")
    @Size(min = 2, max = 100, message = "门店名称长度必须在2-100个字符之间")
    @JsonProperty("name")
    private String name;

    /**
     * 门店编码
     * 必填，格式验证
     */
    @NotBlank(message = "门店编码不能为空")
    @Size(min = 2, max = 50, message = "门店编码长度必须在2-50个字符之间")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "门店编码只能包含字母、数字、下划线和横线")
    @JsonProperty("code")
    private String code;

    /**
     * 门店类型
     * 必填，枚举值验证
     */
    @NotBlank(message = "门店类型不能为空")
    @Pattern(regexp = "^(?i)(flagship|branch|franchise)$", message = "门店类型必须是flagship、branch或franchise")
    @JsonProperty("type")
    private String type;

    /**
     * 地址ID
     * 可选
     */
    @JsonProperty("addressId")
    private Long addressId;

    /**
     * 门店状态
     * 可选，默认为ACTIVE
     */
    @Pattern(regexp = "^(active|inactive|maintenance)$", message = "门店状态必须是active、inactive或maintenance")
    @JsonProperty("status")
    private String status = "active";

    /**
     * 开业日期
     * 可选
     */
    @JsonProperty("openDate")
    private LocalDateTime openDate;

    /**
     * 关闭日期
     * 可选
     */
    @JsonProperty("closeDate")
    private LocalDateTime closeDate;

    /**
     * 扩展信息
     * 可选，JSON格式
     */
    @JsonProperty("extension")
    private Map<String, Object> extension;
}