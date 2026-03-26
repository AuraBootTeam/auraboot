package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * EDI/cXML trading partner entity.
 *
 * <p>Represents an external trading partner (customer or supplier) with whom
 * EDI/cXML documents are exchanged. Stores protocol, endpoint, and authentication
 * configuration for automated B2B document exchange.
 *
 * @since 5.3.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_edi_partner")
public class EdiPartner {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("partner_code")
    private String partnerCode;

    @TableField("partner_name")
    private String partnerName;

    /** CUSTOMER, SUPPLIER */
    @TableField("partner_type")
    private String partnerType;

    /** EDI_X12, EDIFACT, CXML, CUSTOM_XML, JSON_API */
    @TableField("protocol")
    private String protocol;

    @TableField("endpoint_url")
    private String endpointUrl;

    /** NONE, BASIC, OAUTH2, API_KEY, CERTIFICATE */
    @TableField("auth_type")
    private String authType;

    /** Auth credentials (encrypted reference), stored as JSONB */
    @TableField("auth_config")
    private String authConfig;

    @TableField("sender_id")
    private String senderId;

    @TableField("receiver_id")
    private String receiverId;

    @TableField("enabled")
    private Boolean enabled;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableLogic
    @TableField("deleted_flag")
    private Boolean deletedFlag;
}
