package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.dto.PageSchemaCreateRequest;
import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.dto.MetaModelCreateRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.PageSchemaServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class PageSchemaSystemTabIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PageSchemaServiceImpl pageSchemaService;

    @Autowired
    private MetaModelService metaModelService;

    @Autowired
    private MetaFieldService metaFieldService;

    @Test
    @Order(1)
    void findByPageKey_documentModel_shouldInjectConfiguredSystemTabs() {
        String pageKey = createPublishedDetailPage("document");
        PageSchemaDTO dto = pageSchemaService.findByPageKey(pageKey);
        assertThat(dto).isNotNull();
        List<Map<String, Object>> tabs = extractTabs(dto);
        assertThat(tabs).isNotNull();

        List<Map<String, Object>> systemTabs = tabs.stream()
                .filter(t -> Boolean.TRUE.equals(t.get("system")))
                .toList();
        assertThat(systemTabs).hasSize(3);
        assertThat(systemTabs.stream().map(t -> t.get("key")).toList())
                .containsExactly("__comments__", "__activity__", "__field_history__");
    }

    @Test
    @Order(2)
    void findByPageKey_masterModel_shouldInjectActivityAndFieldHistory() {
        String pageKey = createPublishedDetailPage("master");
        PageSchemaDTO dto = pageSchemaService.findByPageKey(pageKey);
        assertThat(dto).isNotNull();
        List<Map<String, Object>> tabs = extractTabs(dto);
        assertThat(tabs).isNotNull();

        List<String> systemKeys = tabs.stream()
                .filter(t -> Boolean.TRUE.equals(t.get("system")))
                .map(t -> (String) t.get("key"))
                .toList();
        assertThat(systemKeys).containsExactly("__comments__", "__activity__", "__field_history__");
        assertThat(systemKeys).doesNotContain("__approval_comments__");
    }

    @Test
    @Order(3)
    void findByPageKey_shouldNotDuplicateSystemTabs() {
        String pageKey = createPublishedDetailPage("master");
        PageSchemaDTO dto1 = pageSchemaService.findByPageKey(pageKey);
        assertThat(dto1).isNotNull();
        List<Map<String, Object>> tabs1 = extractTabs(dto1);
        assertThat(tabs1).isNotNull();

        PageSchemaDTO dto2 = pageSchemaService.findByPageKey(pageKey);
        List<Map<String, Object>> tabs2 = extractTabs(dto2);
        assertThat(tabs2).isNotNull();

        long count1 = tabs1.stream().filter(t -> "__field_history__".equals(t.get("key"))).count();
        long count2 = tabs2.stream().filter(t -> "__field_history__".equals(t.get("key"))).count();
        assertThat(count1).isEqualTo(1);
        assertThat(count2).isEqualTo(1);
    }

    @Test
    @Order(4)
    void findByPageKey_shouldNotInjectDuplicateWhenExplicitFieldHistoryTabExists() {
        String pageKey = createPublishedDetailPage("entity", true);
        PageSchemaDTO dto = pageSchemaService.findByPageKey(pageKey);
        assertThat(dto).isNotNull();

        List<Map<String, Object>> tabs = extractTabs(dto);
        assertThat(tabs).isNotNull();

        long explicitFieldHistoryCount = tabs.stream()
                .filter(t -> "field_history".equals(t.get("key")))
                .count();
        long systemFieldHistoryCount = tabs.stream()
                .filter(t -> "__field_history__".equals(t.get("key")))
                .count();

        assertThat(explicitFieldHistoryCount).isEqualTo(1);
        assertThat(systemFieldHistoryCount).isZero();
    }

    @Test
    @Order(5)
    @SuppressWarnings("unchecked")
    void findByPageKey_systemTabsHaveCorrectStructure() {
        String pageKey = createPublishedDetailPage("master");
        PageSchemaDTO dto = pageSchemaService.findByPageKey(pageKey);
        assertThat(dto).isNotNull();
        List<Map<String, Object>> tabs = extractTabs(dto);
        assertThat(tabs).isNotNull();

        Optional<Map<String, Object>> fieldHistoryOpt = tabs.stream()
                .filter(t -> "__field_history__".equals(t.get("key")))
                .findFirst();
        assertThat(fieldHistoryOpt).isPresent();

        Map<String, Object> fh = fieldHistoryOpt.get();
        assertThat(fh.get("system")).isEqualTo(true);

        Map<String, String> label = (Map<String, String>) fh.get("label");
        assertThat(label.get("en-US")).isEqualTo("Field History");
        assertThat(label.get("zh-CN")).isEqualTo("变更历史");

        List<Map<String, Object>> blocks = (List<Map<String, Object>>) fh.get("blocks");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).get("blockType")).isEqualTo("field-history");
    }

    private String createPublishedDetailPage(String modelCategory) {
        return createPublishedDetailPage(modelCategory, false);
    }

    private String createPublishedDetailPage(String modelCategory, boolean includeExplicitFieldHistoryTab) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String modelCode = "page_tab_model_" + modelCategory + "_" + suffix;
        String tableName = "mt_page_tab_" + modelCategory + "_" + suffix;

        MetaModelCreateRequest modelRequest = new MetaModelCreateRequest();
        modelRequest.setCode(modelCode);
        modelRequest.setDisplayName("Page Tab Model " + suffix);
        modelRequest.setDescription("Integration test model for page schema system tabs");
        modelRequest.setModelCategory(modelCategory);
        modelRequest.setTableName(tableName);
        modelRequest.setAutoPublish(false);

        var model = metaModelService.create(modelRequest);
        MetaFieldCreateRequest fieldRequest = new MetaFieldCreateRequest();
        fieldRequest.setCode("page_tab_field_" + suffix);
        fieldRequest.setDataType("string");
        fieldRequest.setAutoPublish(true);
        MetaFieldDTO field = metaFieldService.create(fieldRequest);
        metaModelService.bindFieldToModel(
                model.getId(),
                field.getId(),
                1,
                true,
                true,
                false,
                null,
                null,
                null,
                null
        );
        metaModelService.publish(model.getPid(), "integration-test");

        String pageKey = modelCode + "_detail_tabs_test";
        PageSchemaCreateRequest pageRequest = new PageSchemaCreateRequest();
        pageRequest.setPageKey(pageKey);
        pageRequest.setModelCode(modelCode);
        pageRequest.setName("Page Tab Test " + suffix);
        pageRequest.setTitle("Page Tab Test " + suffix);
        pageRequest.setKind("detail");
        pageRequest.setBlocks(createTabsBlocks(includeExplicitFieldHistoryTab));
        pageRequest.setSortWeight(0);

        PageSchemaDTO created = pageSchemaService.create(pageRequest);
        pageSchemaService.publish(created.getPid());

        return pageKey;
    }

    private List<Object> createTabsBlocks(boolean includeExplicitFieldHistoryTab) {
        Map<String, Object> tab = new LinkedHashMap<>();
        tab.put("key", "overview");
        tab.put("system", false);
        tab.put("label", Map.of("en-US", "Overview", "zh-CN", "概览"));
        tab.put("blocks", List.of());

        List<Map<String, Object>> tabs = new ArrayList<>();
        tabs.add(tab);

        if (includeExplicitFieldHistoryTab) {
            Map<String, Object> fieldHistoryTab = new LinkedHashMap<>();
            fieldHistoryTab.put("key", "field_history");
            fieldHistoryTab.put("system", false);
            fieldHistoryTab.put("label", Map.of("en-US", "Field History", "zh-CN", "变更历史"));
            fieldHistoryTab.put("blocks", List.of(Map.of("blockType", "field-history", "id", "field_history_block")));
            tabs.add(fieldHistoryTab);
        }

        Map<String, Object> tabsBlock = new LinkedHashMap<>();
        tabsBlock.put("blockType", "tabs");
        tabsBlock.put("tabs", tabs);

        return List.of(tabsBlock);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractTabs(PageSchemaDTO dto) {
        List<Object> blocksList = dto.getBlocks();
        if (blocksList == null) return null;
        for (Object blockObj : blocksList) {
            if (!(blockObj instanceof Map)) continue;
            Map<String, Object> block = (Map<String, Object>) blockObj;
            if ("tabs".equals(block.get("blockType")) || block.containsKey("tabs")) {
                return (List<Map<String, Object>>) block.get("tabs");
            }
        }
        return null;
    }
}
