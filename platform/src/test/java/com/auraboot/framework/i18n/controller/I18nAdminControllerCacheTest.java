package com.auraboot.framework.i18n.controller;

import com.auraboot.framework.i18n.dto.I18nResourceCreateRequest;
import com.auraboot.framework.i18n.dto.I18nResourceUpdateRequest;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.i18n.service.I18nResourceService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Admin resource mutations must invalidate the pack cache, otherwise
 * GET /api/i18n/{locale} keeps serving the stale pack until the 30-minute
 * Caffeine TTL expires and the admin UI's "immediate effect" breaks.
 */
class I18nAdminControllerCacheTest {

    @Test
    void createInvalidatesCreatedLangCache() {
        I18nResourceService resourceService = mock(I18nResourceService.class);
        I18nService i18nService = mock(I18nService.class);
        I18nAdminController controller = new I18nAdminController(
                resourceService, null, i18nService, null, null, null, null, null);

        I18nResourceCreateRequest request = new I18nResourceCreateRequest();
        request.setKey("menu.demo");
        request.setLang("en-US");
        request.setValue("Demo");
        when(resourceService.create(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.create(request);

        verify(i18nService).clearCache("en-US");
    }

    @Test
    void updateInvalidatesAllLocales() {
        I18nResourceService resourceService = mock(I18nResourceService.class);
        I18nService i18nService = mock(I18nService.class);
        I18nAdminController controller = new I18nAdminController(
                resourceService, null, i18nService, null, null, null, null, null);

        I18nResourceUpdateRequest request = new I18nResourceUpdateRequest();
        request.setValue("New value");
        when(resourceService.update(eq("p1"), any())).thenAnswer(inv -> new I18nResource());

        controller.update("p1", request);

        verify(i18nService).clearCache(null);
    }

    @Test
    void deleteInvalidatesResourceLangCache() {
        I18nResourceService resourceService = mock(I18nResourceService.class);
        I18nService i18nService = mock(I18nService.class);
        I18nAdminController controller = new I18nAdminController(
                resourceService, null, i18nService, null, null, null, null, null);

        I18nResource existing = new I18nResource();
        existing.setPid("p2");
        existing.setLang("zh-CN");
        when(resourceService.findByPid("p2")).thenReturn(existing);

        controller.delete("p2");

        verify(resourceService).delete("p2");
        verify(i18nService).clearCache("zh-CN");
    }
}
