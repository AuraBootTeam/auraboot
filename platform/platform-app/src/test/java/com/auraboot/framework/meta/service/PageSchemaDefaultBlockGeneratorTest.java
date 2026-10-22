package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.impl.PageSchemaDefaultBlockGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2B Task 3: verify the deterministic default block generator produces
 * correct blocks for list/detail/form kinds and degrades gracefully on
 * missing inputs.
 *
 * <p>Field-driven cases use the {@code generate(kind, ModelDefinition)}
 * overload directly to avoid round-tripping through MetaModelService
 * (which persists Model-level metadata only; field rows are managed via a
 * separate API and system columns are auto-seeded).
 */
class PageSchemaDefaultBlockGeneratorTest extends BaseIntegrationTest {

    @Autowired
    private PageSchemaDefaultBlockGenerator generator;

    private ModelDefinition buildDef(String code, List<FieldDefinition> fields) {
        return ModelDefinition.builder()
            .code(code)
            .displayName(code + " test")
            .sourceType("physical")
            .tableName("mt_" + code)
            .primaryKey("id")
            .fields(fields)
            .capabilities(ModelCapabilities.builder()
                .list(true).detail(true)
                .create(true).update(true).delete(true).bulkDelete(true)
                .export(true).sort(true).filter(true).paginate(true)
                .sortableFields(List.of("created_at", "name"))
                .filterableFields(List.of("status", "name"))
                .build())
            .build();
    }

    @Test
    void list_kind_produces_three_blocks_filters_toolbar_table() {
        ModelDefinition def = buildDef("p2b_t3_list", List.of(
            FieldDefinition.builder().code("id").dataType("bigint").primaryKey(true).build(),
            FieldDefinition.builder().code("name").dataType("string").build(),
            FieldDefinition.builder().code("status").dataType("string").build(),
            FieldDefinition.builder().code("created_at").dataType("datetime").build(),
            FieldDefinition.builder().code("description").dataType("text").build()
        ));

        List<Map<String, Object>> blocks = generator.generate("list", def);

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0).get("blockType")).isEqualTo("filters");
        assertThat(blocks.get(1).get("blockType")).isEqualTo("toolbar");
        assertThat(blocks.get(2).get("blockType")).isEqualTo("table");

        @SuppressWarnings("unchecked")
        List<String> cols = (List<String>) blocks.get(2).get("columns");
        assertThat(cols).contains("name", "status", "created_at");
        // text type is excluded from list columns
        assertThat(cols).doesNotContain("description");

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) blocks.get(2).get("props");
        assertThat(props).isNotNull();
        assertThat(props.get("pageSize")).isEqualTo(20);
        assertThat(props.get("multiSelect")).isEqualTo(true);
        assertThat(props.get("defaultSortField")).isEqualTo("created_at");
        assertThat(props.get("defaultSortOrder")).isEqualTo("desc");
        assertThat(props.get("rowClickAction")).isEqualTo("detail");

        // filters block: priority-ordered filter fields from whitelist
        @SuppressWarnings("unchecked")
        List<String> filterFields = (List<String>) blocks.get(0).get("fields");
        assertThat(filterFields).contains("status", "name");

        // toolbar block: create + export + bulkDelete presets
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buttons = (List<Map<String, Object>>) blocks.get(1).get("buttons");
        assertThat(buttons).hasSize(3);
        assertThat(buttons.get(0).get("preset")).isEqualTo("create");
        assertThat(buttons.get(1).get("preset")).isEqualTo("export");
        assertThat(buttons.get(2).get("preset")).isEqualTo("bulkDelete");
        assertThat(buttons.get(2).get("requiresSelection")).isEqualTo(true);
    }

    @Test
    void detail_kind_splits_main_and_audit_sections() {
        ModelDefinition def = buildDef("p2b_t3_detail", List.of(
            FieldDefinition.builder().code("id").dataType("bigint").primaryKey(true).build(),
            FieldDefinition.builder().code("name").dataType("string").build(),
            FieldDefinition.builder().code("status").dataType("string").build(),
            FieldDefinition.builder().code("created_at").dataType("datetime").build(),
            FieldDefinition.builder().code("updated_at").dataType("datetime").build()
        ));

        List<Map<String, Object>> blocks = generator.generate("detail", def);

        // Expect: actions_top + section_main + section_audit
        assertThat(blocks).hasSizeGreaterThanOrEqualTo(2);

        Map<String, Object> mainSection = blocks.stream()
            .filter(b -> "section_main".equals(b.get("id")))
            .findFirst()
            .orElseThrow();
        assertThat(mainSection.get("blockType")).isEqualTo("detail-section");
        @SuppressWarnings("unchecked")
        List<String> mainFields = (List<String>) mainSection.get("fields");
        assertThat(mainFields).contains("name", "status", "id");
        assertThat(mainFields).doesNotContain("created_at", "updated_at");

        Map<String, Object> auditBlock = blocks.stream()
            .filter(b -> "section_audit".equals(b.get("id")))
            .findFirst()
            .orElseThrow();
        assertThat(auditBlock.get("defaultCollapsed")).isEqualTo(true);
        assertThat(auditBlock.get("collapsible")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<String> auditFields = (List<String>) auditBlock.get("fields");
        assertThat(auditFields).contains("created_at", "updated_at");

        // actions_top contains edit + delete
        Map<String, Object> actions = blocks.stream()
            .filter(b -> "actions_top".equals(b.get("id")))
            .findFirst()
            .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> btns = (List<Map<String, Object>>) actions.get("buttons");
        assertThat(btns).extracting(b -> b.get("preset")).contains("edit", "delete");
    }

    @Test
    void form_kind_produces_single_section_plus_buttons() {
        ModelDefinition def = buildDef("p2b_t3_form", List.of(
            FieldDefinition.builder().code("id").dataType("bigint").primaryKey(true).build(),
            FieldDefinition.builder().code("name").dataType("string").build(),
            FieldDefinition.builder().code("status").dataType("string").build(),
            FieldDefinition.builder().code("created_at").dataType("datetime").build()
        ));

        List<Map<String, Object>> blocks = generator.generate("form", def);

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).get("blockType")).isEqualTo("form-section");
        assertThat(blocks.get(1).get("blockType")).isEqualTo("form-buttons");

        @SuppressWarnings("unchecked")
        List<String> fields = (List<String>) blocks.get(0).get("fields");
        assertThat(fields).contains("name", "status");
        // primary key + audit fields are excluded from form
        assertThat(fields).doesNotContain("id", "created_at");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> btns = (List<Map<String, Object>>) blocks.get(1).get("buttons");
        assertThat(btns).extracting(b -> b.get("preset")).containsExactly("save", "cancel");
    }

    @Test
    void columns_respect_priority_and_cap() {
        // 10 fields — verify MAX_DEFAULT_COLUMNS (8) applies and priority wins
        ModelDefinition def = buildDef("p2b_t3_cap", List.of(
            FieldDefinition.builder().code("zzz_first").dataType("string").build(),
            FieldDefinition.builder().code("aaa_second").dataType("string").build(),
            FieldDefinition.builder().code("f3").dataType("string").build(),
            FieldDefinition.builder().code("f4").dataType("string").build(),
            FieldDefinition.builder().code("f5").dataType("string").build(),
            FieldDefinition.builder().code("f6").dataType("string").build(),
            FieldDefinition.builder().code("f7").dataType("string").build(),
            FieldDefinition.builder().code("f8").dataType("string").build(),
            FieldDefinition.builder().code("name").dataType("string").build(),
            FieldDefinition.builder().code("status").dataType("string").build()
        ));
        List<Map<String, Object>> blocks = generator.generate("list", def);
        @SuppressWarnings("unchecked")
        List<String> cols = (List<String>) blocks.get(2).get("columns");
        assertThat(cols).hasSize(8);
        // Priority fields (name, status) must appear even though declared last
        assertThat(cols).startsWith("name", "status");
    }

    @Test
    void empty_capabilities_produces_no_filter_fields_and_minimal_toolbar() {
        ModelDefinition def = ModelDefinition.builder()
            .code("p2b_t3_empty")
            .sourceType("sqlView")
            .sourceRef("v_empty")
            .primaryKey("id")
            .fields(List.of(
                FieldDefinition.builder().code("id").dataType("bigint").primaryKey(true).build(),
                FieldDefinition.builder().code("name").dataType("string").build()
            ))
            .capabilities(ModelCapabilities.virtualReadOnly())
            .build();

        List<Map<String, Object>> blocks = generator.generate("list", def);
        assertThat(blocks).hasSize(3);

        @SuppressWarnings("unchecked")
        List<String> filterFields = (List<String>) blocks.get(0).get("fields");
        // virtualReadOnly has filter=true but empty filterableFields whitelist
        assertThat(filterFields).isEmpty();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buttons = (List<Map<String, Object>>) blocks.get(1).get("buttons");
        // virtualReadOnly has no create/update/delete/bulkDelete; export is enabled
        assertThat(buttons).extracting(b -> b.get("preset")).containsExactly("export");
    }

    @Test
    void unknown_model_returns_empty() {
        List<Map<String, Object>> blocks = generator.generate(
            "list", "nonexistent_xyz_" + System.currentTimeMillis());
        assertThat(blocks).isEmpty();
    }

    @Test
    void unknown_kind_returns_empty() {
        ModelDefinition def = buildDef("p2b_t3_unk", List.of(
            FieldDefinition.builder().code("id").dataType("bigint").primaryKey(true).build()
        ));
        assertThat(generator.generate("dashboard", def)).isEmpty();
        assertThat(generator.generate("", def)).isEmpty();
        assertThat(generator.generate(null, def)).isEmpty();
    }

    @Test
    void blank_modelCode_returns_empty() {
        assertThat(generator.generate("list", (String) null)).isEmpty();
        assertThat(generator.generate("list", "")).isEmpty();
        assertThat(generator.generate("list", "   ")).isEmpty();
    }

}
