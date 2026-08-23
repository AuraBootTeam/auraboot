package com.auraboot.framework.meta.contribution;

import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract against the canonical OSS CRM opportunity detail DSL, not a synthetic page shape. */
class CrmOpportunityPageContributionContractTest {

    private final PageSchemaContributionComposer composer = new PageSchemaContributionComposer();

    @Test
    @SuppressWarnings("unchecked")
    void catalogActionCanExtendTheRealOpportunityLineItemsWithoutWholePageOverwrite() throws Exception {
        Path fixture = Path.of("..", "plugins", "crm", "config", "pages",
                "crm_opportunity_common_detail.json").normalize();
        assertThat(Files.isRegularFile(fixture)).as("canonical CRM opportunity detail fixture").isTrue();
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        PageSchemaDTO base = mapper.readValue(fixture.toFile(), PageSchemaDTO.class);
        String originalBlocks = mapper.writeValueAsString(base.getBlocks());

        Map<String, Object> extension = new LinkedHashMap<>(base.getExtension());
        extension.put(PageSchemaContributionComposer.SLOTS_EXTENSION_KEY, List.of(Map.of(
                "id", "line-item-actions",
                "kind", "action",
                "anchor", Map.of(
                        "target", "sub-table-actions",
                        "blockId", "block_line_items"))));
        base.setExtension(extension);

        PageSchemaDTO composed = composer.compose(base, List.of(new PageSchemaContribution(
                "select-product", "catalog-plugin", "line-item-actions",
                PageSchemaContributionKind.ACTION, 100,
                Map.of("code", "select_product", "label", Map.of("en-US", "Select product")))));

        Map<String, Object> lineItems = findBlock(composed.getBlocks(), "block_line_items");
        Map<String, Object> subTable = (Map<String, Object>) lineItems.get("subTable");
        List<Map<String, Object>> actions = (List<Map<String, Object>>) (List<?>) subTable.get("actions");
        assertThat(actions).extracting(action -> action.get("code"))
                .containsExactly("add", "edit", "delete", "select_product");
        assertThat(mapper.writeValueAsString(base.getBlocks())).isEqualTo(originalBlocks);
        assertThat(composed.getPageKey()).isEqualTo("crm_opportunity_common_detail");
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
}
