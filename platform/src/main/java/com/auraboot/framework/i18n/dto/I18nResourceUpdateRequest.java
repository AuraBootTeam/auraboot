package com.auraboot.framework.i18n.dto;

import lombok.Data;

/**
 * I18n Resource Update Request DTO
 *
 * @author AuraBoot
 */
@Data
public class I18nResourceUpdateRequest {

    /**
     * Translated value
     */
    private String value;

    /**
     * Source of the translation (optional)
     * Values: model, page, action, system, ai, import
     */
    private String source;

    /**
     * Status (optional)
     * Values: DRAFT, APPROVED, DEPRECATED
     */
    private String status;

    /**
     * Reference type (optional)
     */
    private String refType;

    /**
     * Reference ID (optional)
     */
    private Long refId;
}
