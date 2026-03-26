package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * EDI message type definition entity.
 *
 * <p>Defines the structure and field mapping for a specific EDI/cXML document type
 * (e.g. EDI 850 Purchase Order, EDI 856 ASN, cXML OrderRequest). Each message type
 * maps fields between the external EDI format and an internal AuraBoot model.
 *
 * @since 5.3.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_edi_message_type")
public class EdiMessageType {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    /** e.g. EDI_850, EDI_856, CXML_ORDER */
    @TableField("type_code")
    private String typeCode;

    @TableField("type_name")
    private String typeName;

    /** EDI_X12, EDIFACT, CXML, CUSTOM_XML, JSON_API */
    @TableField("protocol")
    private String protocol;

    /** INBOUND, OUTBOUND */
    @TableField("direction")
    private String direction;

    /** Mapped AuraBoot model code */
    @TableField("model_code")
    private String modelCode;

    /** Field mapping configuration, stored as JSONB */
    @TableField("mapping_template")
    private String mappingTemplate;

    /** Optional XSLT transform for XML-based protocols */
    @TableField("xslt_template")
    private String xsltTemplate;

    /** Validation rules, stored as JSONB */
    @TableField("validation_rules")
    private String validationRules;

    @TableField("enabled")
    private Boolean enabled;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableLogic
    @TableField("deleted_flag")
    private Boolean deletedFlag;
}
