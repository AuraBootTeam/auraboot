package com.auraboot.framework.i18n.controller;

import com.auraboot.framework.i18n.dto.I18nResourceCreateRequest;
import com.auraboot.framework.i18n.dto.I18nResourceUpdateRequest;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.i18n.service.I18nResourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Map;

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
    void approvedTranslationBecomesVisibleWithoutWaitingForCacheTtl() {
        I18nResourceService resources = mock(I18nResourceService.class);
        I18nService packs = new I18nService();
        org.springframework.test.util.ReflectionTestUtils.setField(packs, "i18nResourceService", resources);
        java.util.concurrent.atomic.AtomicReference<String> value = new java.util.concurrent.atomic.AtomicReference<>("Old");
        when(resources.getResourceMapByLang("ja-JP")).thenAnswer(inv -> Map.of("test.review.key", value.get()));
        org.junit.jupiter.api.Assertions.assertEquals("Old", packs.getValue("ja-JP", "test.review.key"));
        I18nResource resource = I18nResource.builder().lang("ja-JP").status("approved").build();
        when(resources.approve("p1")).thenAnswer(inv -> { value.set("Approved"); return resource; });
        I18nAdminController controller = new I18nAdminController(resources, null, packs, null, null, null, null, null);
        controller.approve("p1");
        org.junit.jupiter.api.Assertions.assertEquals("Approved", packs.getValue("ja-JP", "test.review.key"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"submit", "approve", "reject", "status"})
    void workflowMutationInvalidatesReturnedResourceLocale(String action) {
        I18nResourceService resources = mock(I18nResourceService.class);
        I18nService packs = mock(I18nService.class);
        I18nAdminController controller = new I18nAdminController(resources, null, packs, null, null, null, null, null);
        I18nResource resource = new I18nResource();
        resource.setLang("ja-JP");
        switch (action) {
            case "submit" -> { when(resources.submitReview("p1")).thenReturn(resource); controller.submitReview("p1"); }
            case "approve" -> { when(resources.approve("p1")).thenReturn(resource); controller.approve("p1"); }
            case "reject" -> { when(resources.reject("p1", "revise")).thenReturn(resource); controller.reject("p1", Map.of("reason", "revise")); }
            case "status" -> { when(resources.updateStatus("p1", "deprecated")).thenReturn(resource); controller.updateStatus("p1", Map.of("status", "deprecated")); }
            default -> throw new AssertionError(action);
        }
        verify(packs).clearCache("ja-JP");
    }

    @Test
    void upsertInvalidatesReturnedLocale() {
        I18nResourceService resources = mock(I18nResourceService.class);
        I18nService packs = mock(I18nService.class);
        I18nAdminController controller = new I18nAdminController(resources, null, packs, null, null, null, null, null);
        I18nResource resource = new I18nResource();
        resource.setLang("en-US");
        when(resources.upsert(any())).thenReturn(resource);
        controller.upsert(new I18nResourceCreateRequest());
        verify(packs).clearCache("en-US");
    }

    @Test
    void multiLocaleBatchInvalidatesAllPacks() {
        I18nResourceService resources = mock(I18nResourceService.class);
        I18nService packs = mock(I18nService.class);
        I18nAdminController controller = new I18nAdminController(resources, null, packs, null, null, null, null, null);
        controller.batchUpsert(List.of(new I18nResourceCreateRequest(), new I18nResourceCreateRequest()));
        verify(packs).clearCache(null);
    }

    @Test
    void failedApprovalDoesNotInvalidateOrReturnSuccess() {
        I18nResourceService resources = mock(I18nResourceService.class);
        I18nService packs = mock(I18nService.class);
        I18nAdminController controller = new I18nAdminController(resources, null, packs, null, null, null, null, null);
        when(resources.approve("p1")).thenThrow(new IllegalStateException("invalid transition"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> controller.approve("p1"));
        verifyNoInteractions(packs);
    }

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
