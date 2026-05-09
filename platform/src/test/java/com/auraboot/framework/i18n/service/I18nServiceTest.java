package com.auraboot.framework.i18n.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link I18nService}'s caching, lookup, and fallback contracts.
 * The YAML/JSON loaders rely on classpath resources we don't add — those paths
 * are exercised by the existing integration tests, so here we focus on the
 * database path + cache + value-with-fallback APIs.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class I18nServiceTest {

    @Mock I18nResourceService i18nResourceService;
    private I18nService service;

    @BeforeEach
    void setUp() {
        service = new I18nService();
        ReflectionTestUtils.setField(service, "i18nResourceService", i18nResourceService);
    }

    @AfterEach
    void tearDown() {
        service.clearCache(null);
    }

    @Test
    void getValue_returnsValueFromDb() {
        Map<String, String> dbData = new LinkedHashMap<>();
        dbData.put("greeting", "Hello");
        when(i18nResourceService.getResourceMapByLang("en-US")).thenReturn(dbData);

        assertEquals("Hello", service.getValue("en-US", "greeting"));
        // miss → null
        assertNull(service.getValue("en-US", "missing"));
        // miss with fallback
        assertEquals("DEF", service.getValue("en-US", "missing", "DEF"));
        // hit with fallback
        assertEquals("Hello", service.getValue("en-US", "greeting", "DEF"));
    }

    @Test
    void getI18nData_emptyOrNullLocale_usesDefaultLocale() {
        Map<String, String> dbData = Map.of("k", "v");
        when(i18nResourceService.getResourceMapByLang("zh-CN")).thenReturn(dbData);

        Map<String, Object> r1 = service.getI18nData(null);
        Map<String, Object> r2 = service.getI18nData("");

        assertEquals("v", r1.get("k"));
        assertEquals("v", r2.get("k"));
    }

    @Test
    void getI18nData_cachesAcrossCalls() {
        Map<String, String> dbData = Map.of("k", "v");
        when(i18nResourceService.getResourceMapByLang("en-US")).thenReturn(dbData);

        service.getI18nData("en-US");
        service.getI18nData("en-US");

        // The 2nd call should hit cache → service called once.
        org.mockito.Mockito.verify(i18nResourceService, org.mockito.Mockito.times(1))
                .getResourceMapByLang("en-US");
    }

    @Test
    void clearCache_specificLocale_invalidates() {
        when(i18nResourceService.getResourceMapByLang("en-US")).thenReturn(Map.of("k", "v"));
        service.getI18nData("en-US");
        service.clearCache("en-US");
        service.getI18nData("en-US");
        org.mockito.Mockito.verify(i18nResourceService, org.mockito.Mockito.times(2))
                .getResourceMapByLang("en-US");
    }

    @Test
    void getI18nData_dbThrows_logsAndFallsBackToDefaultLocale() {
        // First call: target locale fails everywhere → recurse to default.
        when(i18nResourceService.getResourceMapByLang("fr-FR"))
                .thenThrow(new RuntimeException("db down"));
        when(i18nResourceService.getResourceMapByLang("zh-CN")).thenReturn(Map.of("k", "v"));

        Map<String, Object> data = service.getI18nData("fr-FR");
        assertThat(data).containsEntry("k", "v");
    }

    @Test
    void getI18nData_unknownLocale_returnsEmptyMap_whenDefaultAlsoEmpty() {
        when(i18nResourceService.getResourceMapByLang(anyString())).thenReturn(Map.of());
        Map<String, Object> data = service.getI18nData("zh-CN"); // direct default — no recursion
        assertThat(data).isEmpty();
    }
}
