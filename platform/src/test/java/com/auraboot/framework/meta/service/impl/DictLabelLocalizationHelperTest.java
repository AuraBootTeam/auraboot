package com.auraboot.framework.meta.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.auraboot.framework.meta.dto.CascadeDictResult;
import com.auraboot.framework.meta.dto.DictDataResult;
import com.auraboot.framework.meta.dto.DictItemData;
import com.auraboot.framework.meta.dto.DictTreeNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Localization of the runtime dict item label. Plugin manifests carry
 * "label:zh-CN"/"label:en" entries that ride along in the item extension
 * "labels" map; the render-time dict reads must serve that map per the request
 * locale instead of pinning the stored single-locale label (EN sessions
 * previously rendered the zh label). Mirrors DictLabelLocalizationHelper
 * contract: exact locale first, language-only fallback, stored label kept when
 * no entry exists.
 */
class DictLabelLocalizationHelperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DictDataResult.DictItemData itemWithMapLabels(Object extension) {
        DictDataResult.DictItemData item = new DictDataResult.DictItemData();
        item.setValue("goods");
        item.setLabel("货物");
        item.setExtension(extension);
        return item;
    }

    @Test
    @DisplayName("Map extension: serves the exact-locale label")
    void servesExactLocaleFromMapExtension() {
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("zh-CN", "货物");
        labels.put("en-US", "Goods");
        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("color", "blue");
        extension.put("labels", labels);

        DictDataResult.DictItemData item = itemWithMapLabels(extension);
        assertEquals("Goods", DictLabelLocalizationHelper.resolveLocalizedLabel(item, "en-US"));
        assertEquals("货物", DictLabelLocalizationHelper.resolveLocalizedLabel(item, "zh-CN"));
    }

    @Test
    @DisplayName("JsonNode extension (dict_item.extra): serves the exact-locale label")
    void servesExactLocaleFromJsonNodeExtension() {
        ObjectNode node = objectMapper.createObjectNode()
                .put("color", "blue")
                .set("labels", objectMapper.createObjectNode().put("zh-CN", "货物").put("en-US", "Goods"));

        DictDataResult.DictItemData item = itemWithMapLabels(node);
        assertEquals("Goods", DictLabelLocalizationHelper.resolveLocalizedLabel(item, "en-US"));
        assertEquals("货物", DictLabelLocalizationHelper.resolveLocalizedLabel(item, "zh-CN"));
    }

    @Test
    @DisplayName("Falls back from en-US to the language-only entry")
    void fallsBackToLanguageOnlyEntry() {
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("zh-CN", "货物");
        labels.put("en", "Goods");
        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("labels", labels);

        assertEquals("Goods",
                DictLabelLocalizationHelper.resolveLocalizedLabel(itemWithMapLabels(extension), "en-US"));
    }

    @Test
    @DisplayName("Blank localized entry falls through to the stored label")
    void blankEntryKeepsStoredLabel() {
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("en-US", "  ");
        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("labels", labels);

        DictDataResult.DictItemData item = itemWithMapLabels(extension);
        assertNull(DictLabelLocalizationHelper.resolveLocalizedLabel(item, "en-US"));
    }

    @Test
    @DisplayName("Missing labels map keeps the stored label (no-translation dicts unchanged)")
    void missingLabelsKeepsStoredLabel() {
        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("color", "blue");

        DictDataResult.DictItemData withoutExtension = itemWithMapLabels(extension);
        assertNull(DictLabelLocalizationHelper.resolveLocalizedLabel(withoutExtension, "en-US"));

        DictDataResult.DictItemData nullExtension = itemWithMapLabels(null);
        assertNull(DictLabelLocalizationHelper.resolveLocalizedLabel(nullExtension, "en-US"));
    }

    @Test
    @DisplayName("localize rewrites items and the value→label map in place; null results untouched")
    void localizeRewritesItemsAndItemMap() {
        Map<String, Object> localized = new LinkedHashMap<>();
        localized.put("zh-CN", "货物");
        localized.put("en-US", "Goods");
        Map<String, Object> localizedExtension = new LinkedHashMap<>();
        localizedExtension.put("labels", localized);

        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("color", "blue");

        DictDataResult result = new DictDataResult();
        DictDataResult.DictItemData localizedItem = itemWithMapLabels(localizedExtension);
        DictDataResult.DictItemData plainItem = itemWithMapLabels(plain);
        plainItem.setValue("service");
        result.setItems(java.util.List.of(localizedItem, plainItem));
        result.setItemMap(new LinkedHashMap<>(Map.of("goods", "货物", "service", "货物")));

        DictLabelLocalizationHelper.localize(result, "en-US");

        assertEquals("Goods", result.getItems().get(0).getLabel());
        assertEquals("货物", result.getItems().get(1).getLabel());
        assertEquals("Goods", result.getItemMap().get("goods"));
        assertEquals("货物", result.getItemMap().get("service"));
    }

    @Test
    @DisplayName("localize is a no-op for null result, blank locale, or null items")
    void localizeGuards() {
        DictLabelLocalizationHelper.localize((DictDataResult) null, "en-US");
        DictLabelLocalizationHelper.localize(new DictDataResult(), "");
        DictDataResult empty = new DictDataResult();
        DictLabelLocalizationHelper.localize(empty, "en-US");
        assertNull(empty.getItems());
    }

    // ==================== cascade reads (children / tree / query) ====================
    //
    // The cascade DTOs carry the item extension since the cascade endpoints
    // gained locale resolution: DictItem.extra rides into DictItemData /
    // DictTreeNode.extension, so the same "labels"-map contract applies.
    // Mirrors the cascade flows: List<DictItemData> children, recursive
    // DictTreeNode, and CascadeDictResult (items + tree + value→label map).

    private ObjectNode extraWithLabels(String zhLabel, String enLabel) {
        return objectMapper.createObjectNode()
                .put("color", "blue")
                .set("labels", objectMapper.createObjectNode().put("zh-CN", zhLabel).put("en", enLabel));
    }

    @Test
    @DisplayName("Cascade children: JsonNode extra (dict_item.extra) serves locale labels; untranslatable items unchanged")
    void localizeCascadeChildrenList() {
        DictItemData localized = new DictItemData("goods", "货物");
        localized.setExtension(extraWithLabels("货物", "Goods"));

        DictItemData plain = new DictItemData("service", "服务");

        java.util.List<DictItemData> items = java.util.List.of(localized, plain);
        DictLabelLocalizationHelper.localize(items, "en-US");

        assertEquals("Goods", items.get(0).getLabel());
        assertEquals("服务", items.get(1).getLabel());
    }

    @Test
    @DisplayName("Cascade children: language-only fallback and blank/no locale guards")
    void localizeCascadeChildrenFallbackAndGuards() {
        DictItemData item = new DictItemData("goods", "货物");
        item.setExtension(extraWithLabels("货物", "Goods"));

        java.util.List<DictItemData> items = new java.util.ArrayList<>(java.util.List.of(item));
        DictLabelLocalizationHelper.localize(items, "zh-CN");
        assertEquals("货物", items.get(0).getLabel());

        DictLabelLocalizationHelper.localize((java.util.List<DictItemData>) null, "en-US");
        DictLabelLocalizationHelper.localize(items, "");
        assertEquals("货物", items.get(0).getLabel());
    }

    @Test
    @DisplayName("Cascade tree: rewrites nested labels recursively; extension-less virtual root keeps its stored label")
    void localizeCascadeTreeRecursively() {
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("zh-CN", "货物");
        labels.put("en", "Goods");
        Map<String, Object> mapExtension = new LinkedHashMap<>();
        mapExtension.put("labels", labels);

        DictTreeNode root = new DictTreeNode("root", "根节点");
        DictTreeNode child = new DictTreeNode("goods", "货物", "root");
        child.setExtension(mapExtension);
        DictTreeNode grandchild = new DictTreeNode("goods-import", "进口货物", "goods");
        grandchild.setExtension(extraWithLabels("进口货物", "Imported Goods"));
        child.addChild(grandchild);
        root.addChild(child);

        DictLabelLocalizationHelper.localize(root, "en-US");

        assertEquals("根节点", root.getLabel());
        assertEquals("Goods", child.getLabel());
        assertEquals("Imported Goods", grandchild.getLabel());
    }

    @Test
    @DisplayName("Cascade query result: items, tree and value→label map all rewritten; maps share item identity")
    void localizeCascadeResult() {
        CascadeDictResult result = new CascadeDictResult("grp_ict_direction");

        DictItemData root = new DictItemData("goods", "货物");
        root.setExtension(extraWithLabels("货物", "Goods"));
        DictItemData child = new DictItemData("service", "服务");
        child.setParentValue("goods");
        child.setExtension(extraWithLabels("服务", "Service"));
        result.addItems(new java.util.ArrayList<>(java.util.List.of(root, child)));

        DictTreeNode treeNode = new DictTreeNode("goods", "货物");
        treeNode.setExtension(extraWithLabels("货物", "Goods"));
        result.setTree(treeNode);

        DictLabelLocalizationHelper.localize(result, "en-US");

        assertEquals("Goods", result.getItems().get(0).getLabel());
        assertEquals("Service", result.getItems().get(1).getLabel());
        assertEquals("Goods", result.getTree().getLabel());
        assertEquals("Goods", result.getItemMap().get("goods"));
        assertEquals("Service", result.getItemMap().get("service"));
        // levelMap / parentChildMap hold the same instances — localized in place
        assertEquals("Service", result.getItemsByLevel(1).get(0).getLabel());
        assertEquals("Service", result.getChildrenByParent("goods").get(0).getLabel());
    }

    @Test
    @DisplayName("Cascade guards: null result/node/tree or blank locale are no-ops")
    void localizeCascadeGuards() {
        DictLabelLocalizationHelper.localize((CascadeDictResult) null, "en-US");
        DictLabelLocalizationHelper.localize((DictTreeNode) null, "en-US");
        DictLabelLocalizationHelper.localize(new CascadeDictResult(), "");

        CascadeDictResult empty = new CascadeDictResult();
        DictLabelLocalizationHelper.localize(empty, "en-US");
        assertEquals(0, empty.getItemMap().size());

        DictTreeNode extensionLess = new DictTreeNode("root", "根节点");
        DictLabelLocalizationHelper.localize(extensionLess, "en-US");
        assertEquals("根节点", extensionLess.getLabel());
    }
}
