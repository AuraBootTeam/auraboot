package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.FormFillRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class FormFillContractTest {
    private FormFillRequest request() {
        return new FormFillRequest("form-1", "customer", "2026-09-12", "Asia/Shanghai", List.of(
                new FormFillRequest.Field("name", "Name", "string", null, null, false),
                new FormFillRequest.Field("quantity", "Quantity", "integer", null, null, false),
                new FormFillRequest.Field("due", "Due", "string", "date", null, false)));
    }

    @Test
    void explicitExtractionHasExactlyOneDraftToolAndAClosedFieldSchema() {
        var resolver = new ChatToolResolver(null, null, null);
        var result = resolver.resolveFormFill(request());
        assertThat(result.tools()).extracting("name").containsExactly("platform_fill_form");
        assertThat(result.isReadOnly()).isTrue();
        assertThat(result.tools().getFirst().getInputSchema()).containsEntry("additionalProperties", false);
    }

    @Test
    void validatesConcreteFactsAndRejectsUnknownFieldsTypesAndImpossibleDates() {
        var schema = FormFillContract.schema(request());
        assertThatCode(() -> FormFillContract.validate(schema, Map.of("fields", Map.of(
                "name", "Acme", "quantity", 12, "due", "2026-09-15"), "reviews", Map.of(
                        "name", Map.of("status", "supported", "quote", "Acme"),
                        "quantity", Map.of("status", "supported", "quote", "12"),
                        "due", Map.of("status", "supported", "quote", "2026-09-15"))),
                "Acme 12 2026-09-15")).doesNotThrowAnyException();
        assertThatThrownBy(() -> FormFillContract.validate(schema, Map.of("fields", Map.of("submit", true))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormFillContract.validate(schema, Map.of("fields", Map.of("name", "Acme"), "submit", true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormFillContract.validate(schema, Map.of("fields", Map.of("quantity", "12"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormFillContract.validate(schema, Map.of("fields", Map.of("due", "2026-02-30"))))
                .isInstanceOf(java.time.DateTimeException.class);
    }

    @Test
    void rejectsLockedFieldsAndMissingTargets() {
        assertThatThrownBy(() -> FormFillContract.schema(new FormFillRequest(null, "customer",
                "2026-09-12", "UTC", request().fields()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormFillContract.schema(new FormFillRequest("f", "customer",
                "2026-09-12", "UTC", List.of(new FormFillRequest.Field("name", "Name", "string", null, null, true)))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void evidenceMustExistInSourceAndAmbiguousFieldsNeverHaveValues() {
        var schema = FormFillContract.schema(request());
        var uncertain = Map.of("status", "ambiguous", "quote", "Acme or Beta", "reason", "multiple_values");
        assertThatCode(() -> FormFillContract.validate(schema,
                Map.of("fields", Map.of(), "reviews", Map.of("name", uncertain)), "Acme or Beta"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> FormFillContract.validate(schema,
                Map.of("fields", Map.of("name", "Acme"), "reviews", Map.of("name", uncertain)), "Acme or Beta"))
                .hasMessageContaining("Ambiguous fields");
        assertThatThrownBy(() -> FormFillContract.validate(schema,
                Map.of("fields", Map.of("name", "Acme"), "reviews", Map.of("name",
                        Map.of("status", "supported", "quote", "Invented quote"))), "Acme"))
                .hasMessageContaining("original text");
        assertThatThrownBy(() -> FormFillContract.validate(schema,
                Map.of("fields", Map.of("name", "Acme")), "Acme"))
                .hasMessageContaining("source evidence");
        assertThatThrownBy(() -> FormFillContract.validate(schema,
                Map.of("fields", Map.of(), "reviews", Map.of("tenant_id", uncertain)), "Acme or Beta"))
                .hasMessageContaining("evidence field");
    }

}
