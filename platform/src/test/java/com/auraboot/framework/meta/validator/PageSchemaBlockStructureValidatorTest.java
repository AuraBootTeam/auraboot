package com.auraboot.framework.meta.validator;

import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.constant.DslRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure-JVM unit tests for {@link PageSchemaBlockStructureValidator}.
 *
 * <p>Covers the hard-reject conditions (missing id / blank blockType /
 * duplicate id, including nested children), the soft-warn-only behaviour for
 * unknown block types (forward-compat for custom blocks), and the union
 * whitelist contents (backend enum ∪ front-end v4 structural blocks).</p>
 */
@DisplayName("PageSchemaBlockStructureValidator unit")
class PageSchemaBlockStructureValidatorTest {

    private static Map<String, Object> block(String id, String blockType) {
        Map<String, Object> b = new HashMap<>();
        b.put("id", id);
        b.put("blockType", blockType);
        return b;
    }

    // ── allowed cases ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("null / empty blocks is allowed (incremental authoring)")
    void nullOrEmptyBlocks_allowed() {
        assertThatCode(() -> PageSchemaBlockStructureValidator.validate(null, "p1")).doesNotThrowAnyException();
        assertThatCode(() -> PageSchemaBlockStructureValidator.validate(List.of(), "p1")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("backend enum blockType with id passes")
    void backendEnumBlockType_passes() {
        assertThatCode(() -> PageSchemaBlockStructureValidator.validate(
                List.of(block("b1", "table"), block("b2", "form")), "p1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("front-end v4 structural blockType with id passes (no warning, in whitelist)")
    void frontendV4StructuralBlockType_passes() {
        assertThatCode(() -> PageSchemaBlockStructureValidator.validate(
                List.of(block("b1", "list"), block("b2", "widget"),
                        block("b3", "column"), block("b4", "field")), "p1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("unknown blockType passes (soft warning only — forward-compat for custom blocks)")
    void unknownBlockType_passesWithoutThrowing() {
        assertThatCode(() -> PageSchemaBlockStructureValidator.validate(
                List.of(block("b1", "totally-made-up-block")), "p1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("nested children with valid ids pass")
    void nestedValidChildren_pass() {
        Map<String, Object> parent = block("p", "tabs");
        parent.put("blocks", List.of(block("c1", "table"), block("c2", "chart")));
        assertThatCode(() -> PageSchemaBlockStructureValidator.validate(List.of(parent), "p1"))
                .doesNotThrowAnyException();
    }

    // ── hard rejects ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("missing id is rejected")
    void missingId_rejected() {
        assertThatThrownBy(() -> PageSchemaBlockStructureValidator.validate(
                List.of(Map.of("blockType", "table")), "p1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("missing block id");
    }

    @Test
    @DisplayName("blank id is rejected")
    void blankId_rejected() {
        assertThatThrownBy(() -> PageSchemaBlockStructureValidator.validate(
                List.of(block("   ", "table")), "p1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("missing block id");
    }

    @Test
    @DisplayName("missing blockType is rejected")
    void missingBlockType_rejected() {
        assertThatThrownBy(() -> PageSchemaBlockStructureValidator.validate(
                List.of(Map.of("id", "b1")), "p1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("missing blockType");
    }

    @Test
    @DisplayName("blank blockType is rejected")
    void blankBlockType_rejected() {
        assertThatThrownBy(() -> PageSchemaBlockStructureValidator.validate(
                List.of(block("b1", "  ")), "p1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("missing blockType");
    }

    @Test
    @DisplayName("duplicate ids at root are rejected")
    void duplicateIdsAtRoot_rejected() {
        assertThatThrownBy(() -> PageSchemaBlockStructureValidator.validate(
                List.of(block("dup", "table"), block("dup", "chart")), "p1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("duplicate block id");
    }

    @Test
    @DisplayName("duplicate id between parent and nested child is rejected (global scope)")
    void duplicateIdAcrossNesting_rejected() {
        Map<String, Object> parent = block("shared", "tabs");
        parent.put("blocks", List.of(block("shared", "table")));
        assertThatThrownBy(() -> PageSchemaBlockStructureValidator.validate(List.of(parent), "p1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("duplicate block id");
    }

    // ── union whitelist contract ───────────────────────────────────────────────

    @Test
    @DisplayName("union whitelist = 36 backend enum codes + 14 front-end v4 structural blocks")
    void unionWhitelist_containsBackendEnumAndFrontendStructural() {
        var known = PageSchemaBlockStructureValidator.knownBlockTypes();

        // all 36 backend enum codes present
        assertThat(known).containsAll(DslRegistry.BlockType.codes());
        assertThat(DslRegistry.BlockType.codes()).hasSize(36);

        // all 14 front-end v4 structural blocks present
        List<String> frontendV4 = Arrays.asList(
                "field", "list", "filter-bar", "filter-field", "column",
                "action-bar", "action", "detail", "dashboard", "widget",
                "columns", "tab", "repeater", "subform");
        assertThat(known).containsAll(frontendV4);

        // union size: 36 + 14 (no overlap between the two sets)
        assertThat(known).hasSize(36 + 14);
    }
}
