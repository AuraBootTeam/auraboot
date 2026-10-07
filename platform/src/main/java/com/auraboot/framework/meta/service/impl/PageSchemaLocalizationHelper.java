package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.service.I18nResourceService;
import com.auraboot.framework.meta.dto.PageSchemaDTO;

/**
 * Resolves the runtime page display name per request locale. Plugin import
 * writes the page display name into the tenant i18n bundle as
 * "page.&lt;pageKey&gt;.title" (en-US + zh-CN); the stored schema.name keeps
 * only one locale. Kept as a static helper so controllers and tests share one
 * implementation without extra bean wiring.
 */
public final class PageSchemaLocalizationHelper {

    private PageSchemaLocalizationHelper() {
    }

    /**
     * Returns the localized page title for the given key/locale, falling back to
     * the stored schema name when the i18n record is absent or blank.
     */
    public static String resolveLocalizedName(I18nResourceService i18nResourceService,
                                              PageSchemaDTO schema,
                                              String i18nKey,
                                              String locale) {
        if (schema.getName() == null || schema.getName().isBlank()) {
            return schema.getName() == null ? "" : schema.getName();
        }
        I18nResource resource = i18nResourceService.findByKeyAndLang(i18nKey, locale);
        if (resource != null && resource.getValue() != null && !resource.getValue().isBlank()) {
            return resource.getValue();
        }
        return schema.getName();
    }
}
