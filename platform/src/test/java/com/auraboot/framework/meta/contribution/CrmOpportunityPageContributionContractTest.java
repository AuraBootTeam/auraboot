package com.auraboot.framework.meta.contribution;

import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract against the canonical OSS CRM opportunity detail DSL, not a synthetic page shape. */
class CrmOpportunityPageContributionContractTest {

    private final PageSchemaContributionComposer composer = new PageSchemaContributionComposer();

    @Test
    @SuppressWarnings("unchecked")
    void realOpportunityPageDeclaresOnlyNeutralLineItemSlots() throws Exception {
        Fixture fixture = readFixture();
        List<Map<String, Object>> slots = (List<Map<String, Object>>) fixture.base().getExtension()
                .get(PageSchemaContributionComposer.SLOTS_EXTENSION_KEY);

        assertThat(slots).containsExactly(
                Map.of(
                        "id", "line-items.blocks",
                        "kind", "block",
                        "anchor", Map.of(
                                "target", "tab-blocks",
                                "blockId", "crm_opportunity_tabs",
                                "tabKey", "line_items")),
                Map.of(
                        "id", "line-items.actions",
                        "kind", "action",
                        "anchor", Map.of(
                                "target", "sub-table-actions",
                                "blockId", "block_line_items")),
                Map.of(
                        "id", "line-items.toolbar-actions",
                        "kind", "action",
                        "anchor", Map.of(
                                "target", "sub-table-toolbar-actions",
                                "blockId", "block_line_items")));
        assertThat(fixture.rawJson())
                .doesNotContain("\"sl_", "\"sl:", "com.auraboot.sales");
    }

    @Test
    @SuppressWarnings("unchecked")
    void realOpportunitySlotsComposeBlockAndActionWithoutWholePageOverwrite() throws Exception {
        Fixture fixture = readFixture();
        ObjectMapper mapper = fixture.mapper();
        PageSchemaDTO base = fixture.base();
        String originalBlocks = mapper.writeValueAsString(base.getBlocks());

        PageSchemaDTO composed = composer.compose(base, List.of(
                new PageSchemaContribution(
                        "catalog-selector", "catalog-plugin", "line-items.blocks",
                        PageSchemaContributionKind.BLOCK, 100,
                        Map.of(
                                "id", "catalog_product_selector",
                                "blockType", "detail-section",
                                "title", Map.of("en-US", "Catalog product selector"),
                                "fields", List.of())),
                new PageSchemaContribution(
                        "select-product", "catalog-plugin", "line-items.actions",
                        PageSchemaContributionKind.ACTION, 100,
                        Map.of("code", "select_product", "label", Map.of("en-US", "Select product"))),
                new PageSchemaContribution(
                        "add-priced-product", "sales-plugin", "line-items.toolbar-actions",
                        PageSchemaContributionKind.ACTION, 100,
                        Map.of("code", "add_priced_product", "label", Map.of("en-US", "Add priced product")))));

        Map<String, Object> tabs = findBlock(composed.getBlocks(), "crm_opportunity_tabs");
        List<Map<String, Object>> lineItemBlocks = findTabBlocks(tabs, "line_items");
        assertThat(lineItemBlocks).extracting(block -> block.get("id"))
                .containsExactly("block_line_items", "catalog_product_selector");
        Map<String, Object> lineItems = findBlock(composed.getBlocks(), "block_line_items");
        Map<String, Object> subTable = (Map<String, Object>) lineItems.get("subTable");
        List<Map<String, Object>> actions = (List<Map<String, Object>>) (List<?>) subTable.get("actions");
        assertThat(actions).extracting(action -> action.get("code"))
                .containsExactly("add", "edit", "delete", "select_product");
        List<Map<String, Object>> toolbarActions =
                (List<Map<String, Object>>) (List<?>) subTable.get("toolbarActions");
        assertThat(toolbarActions).extracting(action -> action.get("code"))
                .containsExactly("add_priced_product");
        assertThat(mapper.writeValueAsString(base.getBlocks())).isEqualTo(originalBlocks);
        assertThat(composed.getPageKey()).isEqualTo("crm_opportunity_common_detail");
    }

    private Fixture readFixture() throws Exception {
        Path fixture = Path.of("..", "plugins", "crm", "config", "pages",
                "crm_opportunity_common_detail.json").normalize();
        assertThat(Files.isRegularFile(fixture)).as("canonical CRM opportunity detail fixture").isTrue();
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        String rawJson = Files.readString(fixture);
        return new Fixture(mapper, mapper.readValue(rawJson, PageSchemaDTO.class), rawJson);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> findTabBlocks(Map<String, Object> tabsBlock, String tabKey) {
        List<Map<String, Object>> tabs = (List<Map<String, Object>>) (List<?>) tabsBlock.get("tabs");
        return (List<Map<String, Object>>) (List<?>) tabs.stream()
                .filter(tab -> tabKey.equals(tab.get("key")))
                .findFirst()
                .orElseThrow()
                .get("blocks");
    }

    private Map<String, Object> findBlock(Object node, String id) {
        if (node instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> found = findBlock(item, id);
                if (found != null) {
                    return found;
                }
            }
        } else if (node instanceof Map<?, ?> map) {
            if (id.equals(map.get("id"))) {
                return (Map<String, Object>) map;
            }
            for (String childKey : List.of("blocks", "children", "tabs")) {
                Map<String, Object> found = findBlock(map.get(childKey), id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private record Fixture(ObjectMapper mapper, PageSchemaDTO base, String rawJson) {
    }
}
