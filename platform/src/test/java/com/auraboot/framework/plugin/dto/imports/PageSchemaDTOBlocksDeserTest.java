package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PageSchemaDTO#blocks} flexible deserializer.
 *
 * <p>Gap2 fix: {@code blocks} must be deserializable from both array form
 * ({@code [...]}) and bare-object form ({@code {...}}) produced by LLMs.
 * The deserializer wraps a bare object in a single-element list.</p>
 */
class PageSchemaDTOBlocksDeserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // =========================================================================
    // Array form (canonical — what we want LLMs to produce after Gap3 fix)
    // =========================================================================

    @Test
    void blocks_arrayForm_singleElement_isDeserializedCorrectly() throws Exception {
        String json = """
                {
                  "pageKey": "book_list",
                  "kind": "list",
                  "layout": {"areas": ["content"]},
                  "blocks": [{
                    "kind": "List",
                    "version": "1.0.0",
                    "id": "list.book",
                    "modelCode": "book"
                  }]
                }
                """;

        PageSchemaDTO dto = objectMapper.readValue(json, PageSchemaDTO.class);

        assertThat(dto.getBlocks()).isNotNull();
        assertThat(dto.getBlocks()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) dto.getBlocks().get(0);
        assertThat(block.get("kind")).isEqualTo("List");
        assertThat(block.get("id")).isEqualTo("list.book");
        assertThat(block.get("modelCode")).isEqualTo("book");
    }

    @Test
    void blocks_arrayForm_multipleElements_allDeserializedCorrectly() throws Exception {
        String json = """
                {
                  "pageKey": "multi_block_page",
                  "kind": "list",
                  "layout": {"areas": ["toolbar", "content"]},
                  "blocks": [
                    { "id": "toolbar-block", "blockType": "toolbar" },
                    { "id": "table-block", "blockType": "table" }
                  ]
                }
                """;

        PageSchemaDTO dto = objectMapper.readValue(json, PageSchemaDTO.class);

        assertThat(dto.getBlocks()).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) dto.getBlocks().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) dto.getBlocks().get(1);
        assertThat(first.get("id")).isEqualTo("toolbar-block");
        assertThat(second.get("id")).isEqualTo("table-block");
    }

    // =========================================================================
    // Bare-object form (LLM compat — Gap2 fix proves this works)
    // =========================================================================

    @Test
    void blocks_bareObjectForm_isWrappedInSingleElementList() throws Exception {
        // LLM sometimes emits a bare object instead of an array — this was the Gap2 bug
        String json = """
                {
                  "pageKey": "book_form",
                  "kind": "form",
                  "layout": {"areas": ["content"]},
                  "blocks": {
                    "kind": "Form",
                    "version": "1.0.0",
                    "id": "form.book",
                    "modelCode": "book"
                  }
                }
                """;

        // Before the fix this would throw MismatchedInputException
        PageSchemaDTO dto = objectMapper.readValue(json, PageSchemaDTO.class);

        assertThat(dto.getBlocks()).isNotNull();
        assertThat(dto.getBlocks()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) dto.getBlocks().get(0);
        assertThat(block.get("kind")).isEqualTo("Form");
        assertThat(block.get("id")).isEqualTo("form.book");
        assertThat(block.get("modelCode")).isEqualTo("book");
    }

    @Test
    void blocks_bareObjectForm_contentPreservedFully() throws Exception {
        // Ensure nested structure inside the bare object is not lost
        String json = """
                {
                  "pageKey": "detail_page",
                  "kind": "detail",
                  "layout": {"areas": ["content"]},
                  "blocks": {
                    "kind": "Detail",
                    "id": "detail.item",
                    "layout": {
                      "areas": ["content"],
                      "areasConfig": { "content": {"type": "flex", "direction": "column"} }
                    },
                    "areas": {
                      "content": {
                        "blocks": [{"id": "info", "blockType": "description"}]
                      }
                    }
                  }
                }
                """;

        PageSchemaDTO dto = objectMapper.readValue(json, PageSchemaDTO.class);

        assertThat(dto.getBlocks()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) dto.getBlocks().get(0);
        assertThat(block).containsKey("layout");
        assertThat(block).containsKey("areas");
        // Verify nested structure was preserved
        @SuppressWarnings("unchecked")
        Map<String, Object> areas = (Map<String, Object>) block.get("areas");
        assertThat(areas).containsKey("content");
    }

    // =========================================================================
    // Null / empty forms
    // =========================================================================

    @Test
    void blocks_nullValue_isDeserializedAsNull() throws Exception {
        String json = """
                {
                  "pageKey": "page_no_blocks",
                  "kind": "list",
                  "layout": {"areas": []},
                  "blocks": null
                }
                """;

        PageSchemaDTO dto = objectMapper.readValue(json, PageSchemaDTO.class);

        assertThat(dto.getBlocks()).isNull();
    }

    @Test
    void blocks_emptyArray_isDeserializedAsEmptyList() throws Exception {
        String json = """
                {
                  "pageKey": "page_empty_blocks",
                  "kind": "list",
                  "layout": {"areas": []},
                  "blocks": []
                }
                """;

        PageSchemaDTO dto = objectMapper.readValue(json, PageSchemaDTO.class);

        assertThat(dto.getBlocks()).isNotNull();
        assertThat(dto.getBlocks()).isEmpty();
    }

    // =========================================================================
    // Round-trip via PluginManifestExtended (mirrors apply() deserialization path)
    // =========================================================================

    @Test
    void pluginManifest_pages_arrayBlocks_deserializedCorrectly() throws Exception {
        // This mirrors the buildPluginManifestJson + objectMapper.readValue path in apply()
        String manifestJson = """
                {
                  "pluginId": "nl-test_mod",
                  "namespace": "test_mod",
                  "version": "1.0.0",
                  "displayName": "test_mod",
                  "pages": [
                    {
                      "pageKey": "item_list",
                      "kind": "list",
                      "layout": {"areas": ["content"]},
                      "blocks": [{
                        "kind": "List",
                        "id": "list.item",
                        "modelCode": "item"
                      }]
                    }
                  ]
                }
                """;

        PluginManifestExtended manifest = objectMapper.readValue(manifestJson, PluginManifestExtended.class);

        assertThat(manifest.getPages()).hasSize(1);
        PageSchemaDTO page = manifest.getPages().get(0);
        assertThat(page.getPageKey()).isEqualTo("item_list");
        assertThat(page.getBlocks()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) page.getBlocks().get(0);
        assertThat(block.get("id")).isEqualTo("list.item");
    }

    @Test
    void pluginManifest_pages_bareObjectBlocks_deserializedCorrectly() throws Exception {
        // Compat: LLM-generated manifest with bare object blocks must not crash apply()
        String manifestJson = """
                {
                  "pluginId": "nl-legacy_mod",
                  "namespace": "legacy_mod",
                  "version": "1.0.0",
                  "displayName": "legacy_mod",
                  "pages": [
                    {
                      "pageKey": "order_form",
                      "kind": "form",
                      "layout": {"areas": ["content"]},
                      "blocks": {
                        "kind": "Form",
                        "id": "form.order",
                        "modelCode": "order"
                      }
                    }
                  ]
                }
                """;

        PluginManifestExtended manifest = objectMapper.readValue(manifestJson, PluginManifestExtended.class);

        assertThat(manifest.getPages()).hasSize(1);
        PageSchemaDTO page = manifest.getPages().get(0);
        assertThat(page.getPageKey()).isEqualTo("order_form");
        // bare object was wrapped → size 1
        assertThat(page.getBlocks()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) page.getBlocks().get(0);
        assertThat(block.get("id")).isEqualTo("form.order");
    }
}
