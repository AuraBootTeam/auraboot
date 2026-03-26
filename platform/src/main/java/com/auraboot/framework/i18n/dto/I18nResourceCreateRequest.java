package com.auraboot.framework.i18n.dto;

import lombok.Data;

/**
 * I18n Resource Create Request DTO
 *
 * @author AuraBoot
 */
@Data
public class I18nResourceCreateRequest {

    /**
     * I18n key (e.g., model.device.name.label)
     */
    private String key;

    /**
     * Language code (e.g., zh-CN, en-US)
     */
    private String lang;

    /**
     * Translated value
     */
    private String value;

    /**
     * Source of the translation (optional, defaults to 'import')
     * Values: model, page, action, system, ai, import
     */
    private String source;
}
