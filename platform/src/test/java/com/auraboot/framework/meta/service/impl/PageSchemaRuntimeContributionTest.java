package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.authoring.workspace.AuthoringRuntimePageMaterializer;
import com.auraboot.framework.meta.contribution.PageSchemaContribution;
import com.auraboot.framework.meta.contribution.PageSchemaContributionComposer;
import com.auraboot.framework.meta.contribution.PageSchemaContributionKind;
import com.auraboot.framework.meta.contribution.PageSchemaContributionProvider;
import com.auraboot.framework.meta.converter.PageSchemaConverter;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.service.AutoPermissionAssignmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PageSchemaRuntimeContributionTest {

    private final PageSchemaMapper mapper = mock(PageSchemaMapper.class);
    private final PageSchemaConverter converter = mock(PageSchemaConverter.class);
    private final AutoPermissionAssignmentService permissionService = mock(AutoPermissionAssignmentService.class);
    private final MetaModelMapper metaModelMapper = mock(MetaModelMapper.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final MetaModelService metaModelService = mock(MetaModelService.class);
    private final PageSchemaDefaultBlockGenerator defaultBlockGenerator = mock(PageSchemaDefaultBlockGenerator.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuthoringRuntimePageMaterializer materializer = mock(AuthoringRuntimePageMaterializer.class);
    private final PageSchemaContributionComposer composer = spy(new PageSchemaContributionComposer());
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PageSchemaContributionProvider> providerObject = mock(ObjectProvider.class);

    private PageSchemaServiceImpl service;
    private PageSchema entity;
    private PageSchemaDTO materialized;

    @BeforeEach
    void setUp() {
        service = new PageSchemaServiceImpl(mapper, converter, permissionService, metaModelMapper,
                eventPublisher, metaModelService, defaultBlockGenerator, objectMapper, materializer,
                composer, providerObject);
        entity = new PageSchema();
        PageSchemaDTO baseline = new PageSchemaDTO();
        materialized = pageWithSlot();
        when(mapper.selectPublishedByPid("page-pid")).thenReturn(entity);
        when(converter.toDTO(entity)).thenReturn(baseline);
        when(materializer.materialize(baseline)).thenReturn(materialized);
    }

    @Test
    void noActiveProviderIsStrictNoOpAfterRuntimeMaterialization() {
        when(providerObject.getIfAvailable(any())).thenReturn(PageSchemaContributionProvider.none());

        PageSchemaDTO result = service.findRuntimeByPid("page-pid");

        assertThat(result).isSameAs(materialized);
        verify(composer).compose(materialized, List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void activeProviderIsComposedAfterRuntimeMaterialization() {
        PageSchemaContribution contribution = new PageSchemaContribution(
                "catalog-block", "catalog-plugin", "related-content", PageSchemaContributionKind.BLOCK,
                0, Map.of("id", "catalog", "blockType", "sub-table"));
        PageSchemaContributionProvider provider = page -> List.of(contribution);
        when(providerObject.getIfAvailable(any())).thenReturn(provider);

        PageSchemaDTO result = service.findRuntimeByPid("page-pid");

        Map<String, Object> tabsBlock = (Map<String, Object>) result.getBlocks().get(0);
        List<Map<String, Object>> tabs = (List<Map<String, Object>>) (List<?>) tabsBlock.get("tabs");
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) (List<?>) tabs.get(0).get("blocks");
        assertThat(blocks).extracting(block -> block.get("id")).containsExactly("base", "catalog");
        assertThat(materialized.getBlocks().toString()).doesNotContain("catalog");
        verify(providerObject).getIfAvailable(any());
    }

    @Test
    void authoringLookupReturnsUncomposedBaseSoReimportCannotSeeOldContributions() {
        PageSchemaDTO base = pageWithSlot();
        base.getBlocks().add(Map.of("id", "base-only", "blockType", "text"));
        when(mapper.selectAnyByPageKey("opportunity-detail")).thenReturn(entity);
        when(converter.toDTO(entity)).thenReturn(base);

        PageSchemaDTO result = service.findAnyByPageKey("opportunity-detail");

        assertThat(result).isSameAs(base);
        assertThat(result.getBlocks()).extracting(block -> String.valueOf(((Map<?, ?>) block).get("id")))
                .containsExactly("detail-tabs", "base-only");
        verifyNoInteractions(materializer, providerObject);
    }

    private PageSchemaDTO pageWithSlot() {
        Map<String, Object> tab = new LinkedHashMap<>();
        tab.put("key", "related");
        tab.put("blocks", new ArrayList<>(List.of(Map.of("id", "base", "blockType", "text"))));
        Map<String, Object> tabs = new LinkedHashMap<>();
        tabs.put("id", "detail-tabs");
        tabs.put("blockType", "tabs");
        tabs.put("tabs", new ArrayList<>(List.of(tab)));
        Map<String, Object> anchor = Map.of(
                "target", "tab-blocks", "blockId", "detail-tabs", "tabKey", "related");
        Map<String, Object> slot = Map.of("id", "related-content", "kind", "block", "anchor", anchor);

        PageSchemaDTO page = new PageSchemaDTO();
        page.setPageKey("opportunity-detail");
        page.setKind("list"); // keep downstream system-tab enrichment a no-op in this focused test
        page.setBlocks(new ArrayList<>(List.of(tabs)));
        page.setExtension(Map.of(PageSchemaContributionComposer.SLOTS_EXTENSION_KEY, List.of(slot)));
        return page;
    }
}
