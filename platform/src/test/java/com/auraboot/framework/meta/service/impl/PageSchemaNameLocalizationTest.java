package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.service.I18nResourceService;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Localization of the runtime page-schema name. Plugin import writes the page
 * display name into the tenant i18n bundle as page.<pageKey>.title (en-US +
 * zh-CN); the runtime read path must serve that value per the request locale
 * instead of pinning the stored single-locale name (EN pages previously
 * rendered the zh title).
 */
@ExtendWith(MockitoExtension.class)
class PageSchemaNameLocalizationTest {

    @Mock
    private PageSchemaService pageSchemaService;

    @Mock
    private I18nResourceService i18nResourceService;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(100L, 1L, "test", "tester");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private PageSchemaDTO schemaWithName(String name) {
        PageSchemaDTO dto = new PageSchemaDTO();
        dto.setPageKey("showcase_all_fields_list");
        dto.setName(name);
        return dto;
    }

    private I18nResource resource(String value) {
        I18nResource r = new I18nResource();
        r.setI18nKey("page.showcase_all_fields_list.title");
        r.setLang("en-US");
        r.setValue(value);
        return r;
    }

    @Test
    @DisplayName("overrides the stored name with the localized title when found")
    void overridesWithLocalizedTitle() {
        when(i18nResourceService.findByKeyAndLang("page.showcase_all_fields_list.title", "en-US"))
                .thenReturn(resource("All Field Types"));

        String resolved = PageSchemaLocalizationHelper.resolveLocalizedName(
                i18nResourceService, schemaWithName("全字段展示列表"),
                "page.showcase_all_fields_list.title", "en-US");

        assertEquals("All Field Types", resolved);
    }

    @Test
    @DisplayName("keeps the stored name when the i18n record is absent")
    void keepsStoredNameWhenAbsent() {
        when(i18nResourceService.findByKeyAndLang(anyString(), anyString())).thenReturn(null);

        String resolved = PageSchemaLocalizationHelper.resolveLocalizedName(
                i18nResourceService, schemaWithName("全字段展示列表"),
                "page.showcase_all_fields_list.title", "en-US");

        assertEquals("全字段展示列表", resolved);
    }

    @Test
    @DisplayName("ignores blank localized values")
    void ignoresBlankValues() {
        when(i18nResourceService.findByKeyAndLang(anyString(), anyString()))
                .thenReturn(resource("  "));

        String resolved = PageSchemaLocalizationHelper.resolveLocalizedName(
                i18nResourceService, schemaWithName("全字段展示列表"),
                "page.showcase_all_fields_list.title", "en-US");

        assertEquals("全字段展示列表", resolved);
    }

    @Test
    @DisplayName("skips resolution when the stored name is blank")
    void skipsBlankStoredName() {
        String resolved = PageSchemaLocalizationHelper.resolveLocalizedName(
                i18nResourceService, schemaWithName(""), "page.showcase_all_fields_list.title", "en-US");

        assertEquals("", resolved);
        verifyNoInteractions();
    }

    private void verifyNoInteractions() {
        org.mockito.Mockito.verifyNoInteractions(i18nResourceService);
    }
}
