package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.meta.dto.ModelDefinition;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Hermetic fixture-admission counterexamples; never substitutes for native DDL/projection proof. */
class ProductQuoteNativePhysicalModelTest {
    private static final String MODEL = "pr_supplier_common";
    private static final String TABLE = "mt_pr_supplier_common";
    private static final Long TENANT = 19L;

    private Map<String, Object> rawModel() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 81L);
        row.put("code", MODEL);
        row.put("tenant_id", TENANT);
        row.put("plugin_pid", "actual-test-owner");
        row.put("status", "published");
        row.put("table_name", null);
        return row;
    }

    private Map<String, Object> owner() {
        return Map.of("pid", "actual-test-owner", "tenant_id", TENANT);
    }

    private ModelDefinition projected() {
        var value = ModelDefinition.builder().build();
        value.setId(81L);
        value.setCode(MODEL);
        value.setStatus("published");
        value.setTableName(TABLE);
        return value;
    }

    @Test void nullableAuthoringTableRequiresAnExactCanonicalProjectionAndExistingPhysicalTable() {
        ProductQuoteReminderNativeFacet.verifyImportedPhysicalModel(rawModel(), owner(),
                Optional.of(projected()), TABLE, TABLE, TENANT, MODEL);
    }

    @Test void missingDefinitionOrPhysicalTableFailsWithoutFallback() {
        assertThatThrownBy(() -> ProductQuoteReminderNativeFacet.verifyImportedPhysicalModel(rawModel(), owner(),
                Optional.empty(), TABLE, TABLE, TENANT, MODEL)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> ProductQuoteReminderNativeFacet.verifyImportedPhysicalModel(rawModel(), owner(),
                Optional.of(projected()), TABLE, null, TENANT, MODEL)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> ProductQuoteReminderNativeFacet.verifyImportedPhysicalModel(rawModel(), owner(),
                Optional.of(projected()), "mt_wrong_model", "mt_wrong_model", TENANT, MODEL)).isInstanceOf(AssertionError.class);
    }

    @Test void foreignOrDifferentOwnerFailsEvenWithAnExistingCanonicalTable() {
        assertThatThrownBy(() -> ProductQuoteReminderNativeFacet.verifyImportedPhysicalModel(rawModel(),
                Map.of("pid", "actual-test-owner", "tenant_id", 20L), Optional.of(projected()),
                TABLE, TABLE, TENANT, MODEL)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> ProductQuoteReminderNativeFacet.verifyImportedPhysicalModel(rawModel(),
                Map.of("pid", "other-owner", "tenant_id", TENANT), Optional.of(projected()),
                TABLE, TABLE, TENANT, MODEL)).isInstanceOf(AssertionError.class);
        var foreign = rawModel();
        foreign.put("tenant_id", 20L);
        assertThatThrownBy(() -> ProductQuoteReminderNativeFacet.verifyImportedPhysicalModel(foreign, owner(),
                Optional.of(projected()), TABLE, TABLE, TENANT, MODEL)).isInstanceOf(AssertionError.class);
    }

    @Test void wrongRegisteredIdentityOrUnpublishedProjectionCannotBorrowAValidTable() {
        var wrong = projected();
        wrong.setId(82L);
        assertThatThrownBy(() -> ProductQuoteReminderNativeFacet.verifyImportedPhysicalModel(rawModel(), owner(),
                Optional.of(wrong), TABLE, TABLE, TENANT, MODEL)).isInstanceOf(AssertionError.class);
        var unpublished = projected();
        unpublished.setStatus("draft");
        assertThatThrownBy(() -> ProductQuoteReminderNativeFacet.verifyImportedPhysicalModel(rawModel(), owner(),
                Optional.of(unpublished), TABLE, TABLE, TENANT, MODEL)).isInstanceOf(AssertionError.class);
    }
}
