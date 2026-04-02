package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.RelationDefinition;
import com.auraboot.framework.meta.exception.TemporalParseException;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doReturn;

class PayloadTemporalNormalizerTest extends BaseIntegrationTest {

    @Autowired
    PayloadTemporalNormalizer normalizer;

    @MockitoSpyBean
    MetaModelService metaModelService;

    private ModelDefinition modelWithFields(FieldDefinition... fields) {
        return ModelDefinition.builder()
            .code("test_model")
            .fields(List.of(fields))
            .build();
    }

    private FieldDefinition dateField(String code) {
        return FieldDefinition.builder().code(code).dataType("date").build();
    }

    private FieldDefinition datetimeField(String code) {
        return FieldDefinition.builder().code(code).dataType("datetime").build();
    }

    @Test
    void dateString_convertedToLocalDate() {
        var model = modelWithFields(dateField("due_date"));
        Map<String, Object> payload = new HashMap<>();
        payload.put("due_date", "2026-03-18");

        normalizer.normalize(payload, model);

        assertThat(payload.get("due_date")).isInstanceOf(LocalDate.class);
        assertThat((LocalDate) payload.get("due_date")).isEqualTo(LocalDate.of(2026, 3, 18));
    }

    @Test
    void datetimeString_withOffset_convertedToInstant() {
        var model = modelWithFields(datetimeField("created_at"));
        Map<String, Object> payload = new HashMap<>();
        payload.put("created_at", "2026-03-18T10:30:00+08:00");

        normalizer.normalize(payload, model);

        assertThat(payload.get("created_at")).isInstanceOf(Instant.class);
    }

    @Test
    void datetimeString_withoutOffset_throws400() {
        var model = modelWithFields(datetimeField("created_at"));
        Map<String, Object> payload = new HashMap<>();
        payload.put("created_at", "2026-03-18T10:30:00");  // no offset

        assertThatThrownBy(() -> normalizer.normalize(payload, model))
            .isInstanceOf(TemporalParseException.class)
            .hasMessageContaining("created_at");
    }

    @Test
    void instantValue_isIdempotent() {
        var model = modelWithFields(datetimeField("created_at"));
        Instant original = Instant.parse("2026-03-18T02:30:00Z");
        Map<String, Object> payload = new HashMap<>();
        payload.put("created_at", original);

        normalizer.normalize(payload, model);

        assertThat(payload.get("created_at")).isSameAs(original);
    }

    @Test
    void localDateValue_isIdempotent() {
        var model = modelWithFields(dateField("due_date"));
        LocalDate original = LocalDate.of(2026, 3, 18);
        Map<String, Object> payload = new HashMap<>();
        payload.put("due_date", original);

        normalizer.normalize(payload, model);

        assertThat(payload.get("due_date")).isSameAs(original);
    }

    @Test
    void invalidDateString_throwsTemporalParseException() {
        var model = modelWithFields(dateField("due_date"));
        Map<String, Object> payload = new HashMap<>();
        payload.put("due_date", "not-a-date");

        assertThatThrownBy(() -> normalizer.normalize(payload, model))
            .isInstanceOf(TemporalParseException.class)
            .hasMessageContaining("due_date");
    }

    @Test
    void nonTemporalField_notTouched() {
        FieldDefinition strField = FieldDefinition.builder()
            .code("name").dataType("string").build();
        var model = modelWithFields(strField);
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "hello");

        normalizer.normalize(payload, model);

        assertThat(payload.get("name")).isEqualTo("hello");
    }

    @Test
    void nullPayloadValue_skipped() {
        var model = modelWithFields(dateField("due_date"));
        Map<String, Object> payload = new HashMap<>();
        payload.put("due_date", null);

        assertThatCode(() -> normalizer.normalize(payload, model))
            .doesNotThrowAnyException();
    }

    @Test
    void unexpectedJavaType_forDateField_originalValuePreserved() {
        var model = modelWithFields(dateField("due_date"));
        Integer unexpectedValue = 12345;  // not a String or LocalDate
        Map<String, Object> payload = new HashMap<>();
        payload.put("due_date", unexpectedValue);

        assertThatCode(() -> normalizer.normalize(payload, model))
            .doesNotThrowAnyException();
        // Original value must be preserved, not overwritten with null
        assertThat(payload.get("due_date")).isEqualTo(unexpectedValue);
    }

    // ===== Recursive sub-table normalization tests =====

    @Test
    void normalize_subTableDateField_convertedToLocalDate() {
        // Set up child model with a date field
        ModelDefinition childModel = ModelDefinition.builder()
            .code("test_order_line")
            .fields(List.of(dateField("delivery_date")))
            .build();
        doReturn(Optional.of(childModel))
            .when(metaModelService).getModelDefinition("test_order_line");

        // Set up parent model with ONE_TO_MANY relation
        RelationDefinition relation = RelationDefinition.builder()
            .name("lines")
            .sourceModel("test_order")
            .targetModel("test_order_line")
            .relationType(RelationDefinition.RelationType.ONE_TO_MANY)
            .build();
        ModelDefinition parentModel = ModelDefinition.builder()
            .code("test_order")
            .fields(List.of(dateField("order_date")))
            .relations(List.of(relation))
            .build();

        // Build payload with top-level and nested date fields
        Map<String, Object> childRow = new HashMap<>();
        childRow.put("delivery_date", "2026-03-20");
        Map<String, Object> payload = new HashMap<>();
        payload.put("order_date", "2026-03-18");
        payload.put("lines", new ArrayList<>(List.of(childRow)));

        normalizer.normalize(payload, parentModel);

        // Top-level field converted
        assertThat(payload.get("order_date")).isInstanceOf(LocalDate.class);
        assertThat((LocalDate) payload.get("order_date")).isEqualTo(LocalDate.of(2026, 3, 18));

        // Nested field also converted
        @SuppressWarnings("unchecked")
        Map<String, Object> normalizedRow = (Map<String, Object>) ((List<?>) payload.get("lines")).get(0);
        assertThat(normalizedRow.get("delivery_date")).isInstanceOf(LocalDate.class);
        assertThat((LocalDate) normalizedRow.get("delivery_date")).isEqualTo(LocalDate.of(2026, 3, 20));
    }

    @Test
    void normalize_subTableDatetimeField_convertedToInstant() {
        // Child model with a datetime field
        ModelDefinition childModel = ModelDefinition.builder()
            .code("test_activity_log")
            .fields(List.of(datetimeField("logged_at")))
            .build();
        doReturn(Optional.of(childModel))
            .when(metaModelService).getModelDefinition("test_activity_log");

        RelationDefinition relation = RelationDefinition.builder()
            .name("logs")
            .sourceModel("test_order")
            .targetModel("test_activity_log")
            .relationType(RelationDefinition.RelationType.ONE_TO_MANY)
            .build();
        ModelDefinition parentModel = ModelDefinition.builder()
            .code("test_order")
            .fields(List.of())
            .relations(List.of(relation))
            .build();

        Map<String, Object> logRow = new HashMap<>();
        logRow.put("logged_at", "2026-03-18T10:30:00+08:00");
        Map<String, Object> payload = new HashMap<>();
        payload.put("logs", new ArrayList<>(List.of(logRow)));

        normalizer.normalize(payload, parentModel);

        @SuppressWarnings("unchecked")
        Map<String, Object> normalizedRow = (Map<String, Object>) ((List<?>) payload.get("logs")).get(0);
        assertThat(normalizedRow.get("logged_at")).isInstanceOf(Instant.class);
    }

    @Test
    void normalize_subTableDatetimeWithoutOffset_throwsTemporalParseException() {
        ModelDefinition childModel = ModelDefinition.builder()
            .code("test_activity_log")
            .fields(List.of(datetimeField("logged_at")))
            .build();
        doReturn(Optional.of(childModel))
            .when(metaModelService).getModelDefinition("test_activity_log");

        RelationDefinition relation = RelationDefinition.builder()
            .name("logs")
            .sourceModel("test_order")
            .targetModel("test_activity_log")
            .relationType(RelationDefinition.RelationType.ONE_TO_MANY)
            .build();
        ModelDefinition parentModel = ModelDefinition.builder()
            .code("test_order")
            .fields(List.of())
            .relations(List.of(relation))
            .build();

        Map<String, Object> logRow = new HashMap<>();
        logRow.put("logged_at", "2026-03-18T10:30:00");  // no offset — must be rejected
        Map<String, Object> payload = new HashMap<>();
        payload.put("logs", new ArrayList<>(List.of(logRow)));

        assertThatThrownBy(() -> normalizer.normalize(payload, parentModel))
            .isInstanceOf(TemporalParseException.class)
            .hasMessageContaining("logged_at");
    }

    @Test
    void normalize_missingChildModel_subTableSkippedGracefully() {
        // MetaModelService returns empty for unknown model
        doReturn(Optional.empty())
            .when(metaModelService).getModelDefinition("unknown_child_model");

        RelationDefinition relation = RelationDefinition.builder()
            .name("items")
            .sourceModel("test_order")
            .targetModel("unknown_child_model")
            .relationType(RelationDefinition.RelationType.ONE_TO_MANY)
            .build();
        ModelDefinition parentModel = ModelDefinition.builder()
            .code("test_order")
            .fields(List.of(dateField("order_date")))
            .relations(List.of(relation))
            .build();

        Map<String, Object> itemRow = new HashMap<>();
        itemRow.put("delivery_date", "2026-03-20");
        Map<String, Object> payload = new HashMap<>();
        payload.put("order_date", "2026-03-18");
        payload.put("items", new ArrayList<>(List.of(itemRow)));

        // Should not throw — missing child model just skips that relation
        assertThatCode(() -> normalizer.normalize(payload, parentModel)).doesNotThrowAnyException();

        // Top-level date is still normalized
        assertThat(payload.get("order_date")).isInstanceOf(LocalDate.class);
        // Nested field left unchanged (no schema to convert it)
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) ((List<?>) payload.get("items")).get(0);
        assertThat(row.get("delivery_date")).isEqualTo("2026-03-20");
    }

    @Test
    void normalize_manyToOneRelation_notRecursed() {
        // MANY_TO_ONE relations should NOT be recursed into
        RelationDefinition manyToOneRelation = RelationDefinition.builder()
            .name("parent")
            .sourceModel("test_order_line")
            .targetModel("test_order")
            .relationType(RelationDefinition.RelationType.MANY_TO_ONE)
            .build();
        ModelDefinition model = ModelDefinition.builder()
            .code("test_order_line")
            .fields(List.of(dateField("delivery_date")))
            .relations(List.of(manyToOneRelation))
            .build();

        // Even though payload has a "parent" key, it should not be recursed as a sub-table
        Map<String, Object> parentRow = new HashMap<>();
        parentRow.put("order_date", "2026-03-18");  // would be a date if recursed
        Map<String, Object> payload = new HashMap<>();
        payload.put("delivery_date", "2026-03-20");
        payload.put("parent", parentRow);  // this is a Map, not a List — should be ignored

        assertThatCode(() -> normalizer.normalize(payload, model)).doesNotThrowAnyException();
        assertThat(payload.get("delivery_date")).isInstanceOf(LocalDate.class);
        // Parent object not touched (not a List, not a ONE_TO_MANY)
        assertThat(((Map<?, ?>) payload.get("parent")).get("order_date")).isEqualTo("2026-03-18");
    }

    @Test
    void normalize_multipleChildRows_allConverted() {
        ModelDefinition childModel = ModelDefinition.builder()
            .code("test_order_line")
            .fields(List.of(dateField("delivery_date")))
            .build();
        doReturn(Optional.of(childModel))
            .when(metaModelService).getModelDefinition("test_order_line");

        RelationDefinition relation = RelationDefinition.builder()
            .name("lines")
            .sourceModel("test_order")
            .targetModel("test_order_line")
            .relationType(RelationDefinition.RelationType.ONE_TO_MANY)
            .build();
        ModelDefinition parentModel = ModelDefinition.builder()
            .code("test_order")
            .fields(List.of())
            .relations(List.of(relation))
            .build();

        Map<String, Object> row1 = new HashMap<>();
        row1.put("delivery_date", "2026-03-20");
        Map<String, Object> row2 = new HashMap<>();
        row2.put("delivery_date", "2026-03-21");
        Map<String, Object> row3 = new HashMap<>();
        row3.put("delivery_date", "2026-03-22");

        Map<String, Object> payload = new HashMap<>();
        payload.put("lines", new ArrayList<>(List.of(row1, row2, row3)));

        normalizer.normalize(payload, parentModel);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) payload.get("lines");
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).get("delivery_date")).isEqualTo(LocalDate.of(2026, 3, 20));
        assertThat(lines.get(1).get("delivery_date")).isEqualTo(LocalDate.of(2026, 3, 21));
        assertThat(lines.get(2).get("delivery_date")).isEqualTo(LocalDate.of(2026, 3, 22));
    }
}
