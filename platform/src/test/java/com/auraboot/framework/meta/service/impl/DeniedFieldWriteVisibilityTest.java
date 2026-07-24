package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.exception.MetaServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A refused field write must not be delivered as silence.
 *
 * <p>Stripping a field the caller may not write is the friendly behaviour for the very common
 * "read a row, edit two fields, send the whole thing back" client — it submitted the forbidden
 * field, but it submitted the value already stored, so nothing was actually denied. A submitted
 * value that <em>differs</em> is a different event: the caller asked for a change and did not get
 * it, and that is exactly the shape of failure the 2026-07-22 outage taught us not to swallow.</p>
 */
class DeniedFieldWriteVisibilityTest {

    private static final String MODEL = "quote";

    private Map<String, Object> stored() {
        Map<String, Object> row = new HashMap<>();
        row.put("cost_price", 80);
        row.put("discount_rate", 5);
        row.put("memo", "old");
        return row;
    }

    private Set<String> stripped(String... codes) {
        return new LinkedHashSet<>(Set.of(codes));
    }

    private Map<String, Object> submitted(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("changing a field you may not write is refused out loud")
    void realDenialIsVisible() {
        MetaServiceException ex = assertThrows(MetaServiceException.class, () ->
                DynamicDataServiceImpl.assertNoDeniedFieldWrites(
                        MODEL, submitted("cost_price", 999), stripped("cost_price"), stored()));

        assertTrue(ex.getMessage().contains("FIELD_WRITE_DENIED"), "got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("cost_price"),
                "the refusal must name the field, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("submitting the stored value for a field you may not write is not a denial")
    void unchangedRoundTripStaysSilent() {
        assertDoesNotThrow(() -> DynamicDataServiceImpl.assertNoDeniedFieldWrites(
                MODEL,
                submitted("cost_price", 80, "memo", "new"),
                stripped("cost_price"),
                stored()));
    }

    @Test
    @DisplayName("a JDBC type round-trip is not an attempted change")
    void typeRoundTripStaysSilent() {
        // The row reads back as Integer while JSON supplies Long. Refusing that would punish
        // a client that changed nothing.
        assertDoesNotThrow(() -> DynamicDataServiceImpl.assertNoDeniedFieldWrites(
                MODEL, submitted("cost_price", 80L), stripped("cost_price"), stored()));
    }

    @Test
    @DisplayName("every denied field is named, not just the first")
    void allDeniedFieldsAreNamed() {
        MetaServiceException ex = assertThrows(MetaServiceException.class, () ->
                DynamicDataServiceImpl.assertNoDeniedFieldWrites(
                        MODEL,
                        submitted("cost_price", 999, "discount_rate", 50),
                        stripped("cost_price", "discount_rate"),
                        stored()));

        assertTrue(ex.getMessage().contains("cost_price") && ex.getMessage().contains("discount_rate"),
                "got: " + ex.getMessage());
    }

    @Test
    @DisplayName("nothing stripped means nothing to report")
    void nothingStrippedIsSilent() {
        assertDoesNotThrow(() -> DynamicDataServiceImpl.assertNoDeniedFieldWrites(
                MODEL, submitted("memo", "new"), stripped(), stored()));
    }

    @Test
    @DisplayName("a stripped field the caller never submitted is not a denial")
    void notSubmittedIsSilent() {
        assertDoesNotThrow(() -> DynamicDataServiceImpl.assertNoDeniedFieldWrites(
                MODEL, submitted("memo", "new"), stripped("cost_price"), stored()));
    }

    @Test
    @DisplayName("no stored row to compare against yields no claim either way")
    void nullExistingRecordIsSilent() {
        assertDoesNotThrow(() -> DynamicDataServiceImpl.assertNoDeniedFieldWrites(
                MODEL, submitted("cost_price", 999), stripped("cost_price"), null));
    }

    @Test
    @DisplayName("setting a forbidden field that was previously null is still a denial")
    void nullToValueIsADenial() {
        Map<String, Object> row = stored();
        row.put("cost_price", null);

        assertThrows(MetaServiceException.class, () ->
                DynamicDataServiceImpl.assertNoDeniedFieldWrites(
                        MODEL, submitted("cost_price", 999), stripped("cost_price"), row));
    }
}
