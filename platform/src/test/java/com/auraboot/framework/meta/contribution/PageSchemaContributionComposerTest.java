package com.auraboot.framework.meta.contribution;

import com.auraboot.framework.meta.dto.PageSchemaDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageSchemaContributionComposerTest {

    private final PageSchemaContributionComposer composer = new PageSchemaContributionComposer();

    @Test
    void emptyContributionsAreAStrictNoOp() {
        PageSchemaDTO base = new PageSchemaDTO();
        base.setExtension(Map.of("malformed-but-inactive", true));

        assertThat(composer.compose(base, List.of())).isSameAs(base);
    }

    @Test
    @SuppressWarnings("unchecked")
    void blockContributionsAreDeterministicAndDoNotMutateBase() {
        PageSchemaDTO base = detailPage(List.of(slot(
                "related-content", "block", "tab-blocks", "detail-tabs", "related")));
        Map<String, Object> later = block("later-block", "text");
        Map<String, Object> first = block("first-block", "sub-table");
        Map<String, Object> tiedFirst = block("tied-first", "text");
        Map<String, Object> tiedSecond = block("tied-second", "text");

        PageSchemaDTO composed = composer.compose(base, List.of(
                contribution("higher-z", "plugin-z", "related-content", PageSchemaContributionKind.BLOCK, 20, later),
                contribution("tie-z", "plugin-a", "related-content", PageSchemaContributionKind.BLOCK, 20, tiedSecond),
                contribution("tie-a", "plugin-a", "related-content", PageSchemaContributionKind.BLOCK, 20, tiedFirst),
                contribution("low", "plugin-a", "related-content", PageSchemaContributionKind.BLOCK, 10, first)));

        assertThat(composed).isNotSameAs(base);
        List<Map<String, Object>> composedBlocks = tabBlocks(composed);
        assertThat(composedBlocks).extracting(block -> block.get("id"))
                .containsExactly("base-related", "tied-first", "tied-second", "later-block", "first-block");
        assertThat(tabBlocks(base)).extracting(block -> block.get("id"))
                .containsExactly("base-related");
        assertThat(composedBlocks.get(1)).isNotSameAs(first);
    }

    @Test
    @SuppressWarnings("unchecked")
    void actionContributionCanTargetDeclaredSubTableActions() {
        PageSchemaDTO base = detailPage(List.of(slot(
                "line-actions", "action", "sub-table-actions", "base-related", null)));
        Map<String, Object> action = new LinkedHashMap<>(Map.of("code", "select_product", "label", "Select product"));

        PageSchemaDTO composed = composer.compose(base, List.of(contribution(
                "select-product-action", "catalog-plugin", "line-actions",
                PageSchemaContributionKind.ACTION, 0, action)));

        Map<String, Object> relatedBlock = findBlock(tabBlocks(composed), "base-related");
        Map<String, Object> subTable = (Map<String, Object>) relatedBlock.get("subTable");
        List<Map<String, Object>> actions = (List<Map<String, Object>>) (List<?>) subTable.get("actions");
        assertThat(actions).extracting(item -> item.get("code"))
                .containsExactly("open", "select_product");
        assertThat(((Map<String, Object>) findBlock(tabBlocks(base), "base-related").get("subTable"))
                .get("actions").toString()).doesNotContain("select_product");
    }

    @Test
    void missingAnchorFailsClosed() {
        PageSchemaDTO base = detailPage(List.of(slot(
                "missing", "block", "tab-blocks", "unknown-tabs", "related")));

        assertThatThrownBy(() -> composer.compose(base, List.of(contribution(
                "block", "plugin", "missing", PageSchemaContributionKind.BLOCK, 0, block("new", "text")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing anchor block");
    }

    @Test
    void wrongKindFailsClosed() {
        PageSchemaDTO base = detailPage(List.of(slot(
                "related-content", "block", "tab-blocks", "detail-tabs", "related")));

        assertThatThrownBy(() -> composer.compose(base, List.of(contribution(
                "action", "plugin", "related-content", PageSchemaContributionKind.ACTION, 0,
                Map.of("code", "select")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind does not match");
    }

    @Test
    void duplicateContributionOrPayloadIdsFailClosed() {
        PageSchemaDTO base = detailPage(List.of(slot(
                "related-content", "block", "tab-blocks", "detail-tabs", "related")));
        PageSchemaContribution first = contribution(
                "duplicate", "plugin-a", "related-content", PageSchemaContributionKind.BLOCK, 0,
                block("new-a", "text"));
        PageSchemaContribution sameContributionId = contribution(
                "duplicate", "plugin-b", "related-content", PageSchemaContributionKind.BLOCK, 1,
                block("new-b", "text"));

        assertThatThrownBy(() -> composer.compose(base, List.of(first, sameContributionId)))
                .hasMessageContaining("duplicate contribution id");

        PageSchemaContribution duplicateBaseBlockId = contribution(
                "different", "plugin", "related-content", PageSchemaContributionKind.BLOCK, 0,
                block("base-related", "text"));
        assertThatThrownBy(() -> composer.compose(base, List.of(duplicateBaseBlockId)))
                .hasMessageContaining("duplicate id");

        Map<String, Object> nestedCollision = block("wrapper", "container");
        nestedCollision.put("children", List.of(block("base-related", "text")));
        assertThatThrownBy(() -> composer.compose(base, List.of(contribution(
                "nested-collision", "plugin", "related-content", PageSchemaContributionKind.BLOCK, 0,
                nestedCollision))))
                .hasMessageContaining("duplicate id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void repeatedActionCodesInDifferentBaseBlocksAreAllowedButTargetDuplicateFails() {
        PageSchemaDTO base = detailPage(List.of(slot(
                "line-actions", "action", "sub-table-actions", "base-related", null)));
        Map<String, Object> secondBlock = block("second-related", "sub-table");
        secondBlock.put("subTable", new LinkedHashMap<>(Map.of(
                "actions", new ArrayList<>(List.of(Map.of("code", "open"))))));
        tabBlocks(base).add(secondBlock);

        PageSchemaDTO composed = composer.compose(base, List.of(contribution(
                "select", "plugin", "line-actions", PageSchemaContributionKind.ACTION, 0,
                Map.of("code", "select"))));
        assertThat(composed).isNotNull();

        assertThatThrownBy(() -> composer.compose(base, List.of(contribution(
                "duplicate-target-action", "plugin", "line-actions", PageSchemaContributionKind.ACTION, 0,
                Map.of("code", "open")))))
                .hasMessageContaining("duplicate action code in target collection");
    }

    @Test
    @SuppressWarnings("unchecked")
    void childrenAreTraversedForAnchorsAndBlockIdentityChecks() {
        Map<String, Object> child = block("child-toolbar", "toolbar");
        child.put("actions", new ArrayList<>());
        Map<String, Object> container = block("container", "container");
        container.put("children", new ArrayList<>(List.of(child)));
        PageSchemaDTO base = new PageSchemaDTO();
        base.setBlocks(new ArrayList<>(List.of(container)));
        base.setExtension(Map.of(PageSchemaContributionComposer.SLOTS_EXTENSION_KEY, List.of(slot(
                "child-actions", "action", "block-actions", "child-toolbar", null))));

        PageSchemaDTO composed = composer.compose(base, List.of(contribution(
                "child-action", "plugin", "child-actions", PageSchemaContributionKind.ACTION, 0,
                Map.of("code", "run"))));

        Map<String, Object> copiedContainer = (Map<String, Object>) composed.getBlocks().get(0);
        List<Map<String, Object>> children = (List<Map<String, Object>>) (List<?>) copiedContainer.get("children");
        assertThat(children.get(0).get("actions").toString()).contains("run");

        ((List<Map<String, Object>>) (List<?>) container.get("children"))
                .add(block("child-toolbar", "text"));
        assertThatThrownBy(() -> composer.compose(base, List.of(contribution(
                "another-action", "plugin", "child-actions", PageSchemaContributionKind.ACTION, 0,
                Map.of("code", "another")))))
                .hasMessageContaining("duplicate id in base page");
    }

    private PageSchemaDTO detailPage(List<Map<String, Object>> slots) {
        Map<String, Object> subTable = new LinkedHashMap<>();
        subTable.put("actions", new ArrayList<>(List.of(Map.of("code", "open"))));
        Map<String, Object> related = block("base-related", "sub-table");
        related.put("subTable", subTable);
        Map<String, Object> relatedTab = new LinkedHashMap<>();
        relatedTab.put("key", "related");
        relatedTab.put("blocks", new ArrayList<>(List.of(related)));
        Map<String, Object> tabs = block("detail-tabs", "tabs");
        tabs.put("tabs", new ArrayList<>(List.of(relatedTab)));

        PageSchemaDTO page = new PageSchemaDTO();
        page.setPageKey("opportunity-detail");
        page.setKind("detail");
        page.setBlocks(new ArrayList<>(List.of(tabs)));
        page.setExtension(Map.of(PageSchemaContributionComposer.SLOTS_EXTENSION_KEY, slots));
        return page;
    }

    private Map<String, Object> slot(String id, String kind, String target, String blockId, String tabKey) {
        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("target", target);
        anchor.put("blockId", blockId);
        if (tabKey != null) {
            anchor.put("tabKey", tabKey);
        }
        return Map.of("id", id, "kind", kind, "anchor", anchor);
    }

    private Map<String, Object> block(String id, String type) {
        return new LinkedHashMap<>(Map.of("id", id, "blockType", type));
    }

    private PageSchemaContribution contribution(String id, String contributor, String slot,
                                                    PageSchemaContributionKind kind, int order,
                                                    Map<String, Object> payload) {
        return new PageSchemaContribution(id, contributor, slot, kind, order, payload);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> tabBlocks(PageSchemaDTO page) {
        Map<String, Object> tabsBlock = (Map<String, Object>) page.getBlocks().get(0);
        List<Map<String, Object>> tabs = (List<Map<String, Object>>) (List<?>) tabsBlock.get("tabs");
        return (List<Map<String, Object>>) (List<?>) tabs.get(0).get("blocks");
    }

    private Map<String, Object> findBlock(List<Map<String, Object>> blocks, String id) {
        return blocks.stream().filter(block -> id.equals(block.get("id"))).findFirst().orElseThrow();
    }
}
