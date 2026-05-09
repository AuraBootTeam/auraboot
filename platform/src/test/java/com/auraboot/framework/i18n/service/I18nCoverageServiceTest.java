package com.auraboot.framework.i18n.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.i18n.dto.I18nCoverageResponse;
import com.auraboot.framework.i18n.mapper.I18nResourceMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class I18nCoverageServiceTest {

    @Mock I18nResourceMapper mapper;
    @InjectMocks I18nCoverageService service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(100L, 1L, "u", "u");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void computeCoverage_baseLocaleHas100Pct_otherShowsCoverage() {
        when(mapper.countByLang(100L)).thenReturn(List.of(
                Map.of("lang", "zh-CN", "count", 100),
                Map.of("lang", "en-US", "count", 80)
        ));
        when(mapper.selectDistinctLangs(100L)).thenReturn(List.of("zh-CN", "en-US"));
        when(mapper.countMissingKeys(eq(100L), eq("zh-CN"), eq("en-US"))).thenReturn(20L);
        when(mapper.selectMissingKeys(eq(100L), eq("zh-CN"), eq("en-US"), eq(50)))
                .thenReturn(List.of("k.a", "k.b"));

        I18nCoverageResponse res = service.computeCoverage();

        assertEquals("zh-CN", res.getBaseLocale());
        assertEquals(100, res.getTotalKeys());
        assertEquals(2, res.getLocales().size());
        // Base locale first
        assertEquals("zh-CN", res.getLocales().get(0).getLocale());
        assertEquals(100.0, res.getLocales().get(0).getCoverage());
        // en-US: 80/100 → 80.0
        assertEquals("en-US", res.getLocales().get(1).getLocale());
        assertEquals(80.0, res.getLocales().get(1).getCoverage());
        assertEquals(20L, res.getLocales().get(1).getMissing());
        assertThat(res.getMissingKeys()).extracting("key").containsExactly("k.a", "k.b");
        assertThat(res.getMissingKeys().get(0).getMissingIn()).contains("en-US");
    }

    @Test
    void computeCoverage_emptyTotalKeys_zeroPct() {
        when(mapper.countByLang(100L)).thenReturn(List.of());
        when(mapper.selectDistinctLangs(100L)).thenReturn(List.of("zh-CN", "en-US"));

        I18nCoverageResponse res = service.computeCoverage();
        assertEquals(0, res.getTotalKeys());
        assertEquals(0.0, res.getLocales().get(0).getCoverage());
    }

    @Test
    void computeCoverage_multipleLocalesSortedByCoverageDesc() {
        when(mapper.countByLang(100L)).thenReturn(List.of(
                Map.of("lang", "zh-CN", "count", 100)
        ));
        when(mapper.selectDistinctLangs(100L)).thenReturn(List.of("zh-CN", "ja-JP", "en-US"));
        when(mapper.countMissingKeys(eq(100L), eq("zh-CN"), eq("ja-JP"))).thenReturn(70L);
        when(mapper.countMissingKeys(eq(100L), eq("zh-CN"), eq("en-US"))).thenReturn(10L);
        when(mapper.selectMissingKeys(eq(100L), eq("zh-CN"), eq("ja-JP"), eq(50)))
                .thenReturn(List.of("k.x"));
        when(mapper.selectMissingKeys(eq(100L), eq("zh-CN"), eq("en-US"), eq(50)))
                .thenReturn(List.of("k.x", "k.y"));

        I18nCoverageResponse res = service.computeCoverage();

        // zh-CN, then en-US (90%), then ja-JP (30%)
        assertEquals("zh-CN", res.getLocales().get(0).getLocale());
        assertEquals("en-US", res.getLocales().get(1).getLocale());
        assertEquals("ja-JP", res.getLocales().get(2).getLocale());
        // k.x present in both ja-JP and en-US
        assertThat(res.getMissingKeys()).extracting("key").contains("k.x", "k.y");
    }
}
