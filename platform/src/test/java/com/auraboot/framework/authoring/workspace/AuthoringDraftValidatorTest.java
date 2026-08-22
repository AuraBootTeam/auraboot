package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringChangeSetSplitter.ChangeItem;
import com.auraboot.framework.authoring.workspace.AuthoringDraftValidator.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringDraftValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuthoringDraftValidator validator = new AuthoringDraftValidator();

    @Test
    void acceptsAValidChangedPropertyWithoutExecutingThePage() throws Exception {
        JsonNode snapshot = snapshot("""
                [{"id":"table-1","blockType":"table",
                  "props":{"defaultFilter":{"status":"OPEN"}}}]
                """);

        ValidationResult result = validator.validate(
                snapshot, List.of(item("item-1", "table-1", "/props/defaultFilter")));

        assertThat(result.valid()).isTrue();
        assertThat(result.errorCount()).isZero();
    }

    @Test
    void returnsLocationOnlyIssuesForInvalidChangedProperties() throws Exception {
        JsonNode snapshot = snapshot("""
                [{"id":"table-1","blockType":"table",
                  "props":{"defaultFilter":"status = OPEN secret-value"}}]
                """);

        ValidationResult result = validator.validate(
                snapshot, List.of(item("item-1", "table-1", "/props/defaultFilter")));

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("DEFAULT_FILTER_INVALID");
            assertThat(issue.changeItemPid()).isEqualTo("item-1");
            assertThat(issue.blockId()).isEqualTo("table-1");
            assertThat(issue.propertyPath()).isEqualTo("/props/defaultFilter");
            assertThat(issue.toString()).doesNotContain("secret-value");
        });
    }

    @Test
    void rejectsDuplicateStableIdsBeforePropertyValidation() throws Exception {
        JsonNode snapshot = snapshot("""
                [{"id":"table-1","blockType":"table"},
                 {"id":"table-1","blockType":"table"}]
                """);

        ValidationResult result = validator.validate(snapshot, List.of());

        assertThat(result.issues()).extracting(issue -> issue.code())
                .containsExactly("BLOCK_ID_DUPLICATE");
    }

    @Test
    void validatesStructureMarkersByTheirTreePostconditionInsteadOfAsJsonPointers()
            throws Exception {
        JsonNode snapshot = snapshot("""
                [{"id":"root","blockType":"list","blocks":[
                  {"id":"table-1","blockType":"table"}]}]
                """);

        ValidationResult result = validator.validate(snapshot, List.of(
                item("create", "table-1", "/$structure/create", "ADD"),
                item("move", "table-1", "/$structure/parent", "MOVE"),
                item("order", "table-1", "/$structure/order", "MOVE"),
                item("remove", "deleted-field", "/$structure/remove", "REMOVE")));

        assertThat(result.valid()).isTrue();
        assertThat(result.errorCount()).isZero();
    }

    @Test
    void rejectsUnknownStructureMarkersAndUnsatisfiedPostconditions() throws Exception {
        JsonNode snapshot = snapshot("""
                [{"id":"root","blockType":"list"}]
                """);

        ValidationResult result = validator.validate(snapshot, List.of(
                item("missing-create", "table-1", "/$structure/create", "ADD"),
                item("present-remove", "root", "/$structure/remove", "REMOVE"),
                item("unknown", "root", "/$structure/unknown", "MOVE")));

        assertThat(result.issues()).extracting(issue -> issue.code())
                .containsExactly(
                        "STRUCTURE_POSTCONDITION_INVALID",
                        "STRUCTURE_POSTCONDITION_INVALID",
                        "STRUCTURE_CHANGE_INVALID");
    }

    private JsonNode snapshot(String blocks) throws Exception {
        return objectMapper.readTree("""
                {"pid":"page-1","blocks":%s}
                """.formatted(blocks));
    }

    private ChangeItem item(String pid, String blockId, String propertyPath) {
        return item(pid, blockId, propertyPath, "REPLACE");
    }

    private ChangeItem item(
            String pid, String blockId, String propertyPath, String operation) {
        return new ChangeItem(
                1, pid, blockId, propertyPath, operation,
                JsonNodeFactory.instance.nullNode(), JsonNodeFactory.instance.nullNode(),
                JsonNodeFactory.instance.arrayNode(), "L2", "GUIDED_INLINE",
                "REQUIRED_REVIEW", "REVERSIBLE", "manifest", 1, 2, 7,
                Instant.parse("2026-08-09T00:00:00Z"), null, null,
                JsonNodeFactory.instance.arrayNode());
    }
}
