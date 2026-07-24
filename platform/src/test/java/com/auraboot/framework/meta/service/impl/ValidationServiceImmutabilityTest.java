package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Field-level domain invariants: {@code immutable} and {@code immutableWhen}.
 *
 * <p>These are invariants, not permissions. Nothing here grants or withholds a role, and
 * the expected behaviour is identical for an admin, a plugin handler, and a command that
 * inherited an aggregate's authority — which is exactly why the check lives in the
 * validation layer rather than in the authorization decision.</p>
 */
class ValidationServiceImmutabilityTest {

    /** validateImmutability never touches the mapper, so a null collaborator is honest here. */
    private final ValidationServiceImpl validation = new ValidationServiceImpl(null);

    private ModelDefinition quoteModel() {
        FieldDefinition price = FieldDefinition.builder()
                .code("price")
                .name("Price")
                .immutableWhen(FieldDefinition.ImmutableWhen.builder()
                        .field("status")
                        .in(List.of("approved", "closed"))
                        .build())
                .build();
        FieldDefinition status = FieldDefinition.builder().code("status").name("Status").build();
        FieldDefinition orderNo = FieldDefinition.builder()
                .code("order_no").name("Order No").immutable(true).build();
        FieldDefinition memo = FieldDefinition.builder().code("memo").name("Memo").build();
        FieldDefinition signedOn = FieldDefinition.builder()
                .code("signed_on").name("Signed On").immutable(true).build();

        return ModelDefinition.builder()
                .code("quote")
                .fields(List.of(price, status, orderNo, memo, signedOn))
                .build();
    }

    private Map<String, Object> storedApprovedQuote() {
        Map<String, Object> row = new HashMap<>();
        row.put("status", "approved");
        row.put("price", 100);
        row.put("order_no", "Q1001");
        row.put("memo", "old memo");
        return row;
    }

    private Map<String, Object> change(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    // ---------- unconditional immutability ----------

    @Test
    @DisplayName("immutable field: changing it is rejected")
    void immutableFieldChangeRejected() {
        ValidationResult r = validation.validateImmutability(
                quoteModel(), change("order_no", "Q9999"), storedApprovedQuote());

        assertFalse(r.getValid());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("Order No")),
                "error must name the offending field, got: " + r.getErrors());
    }

    @Test
    @DisplayName("immutable field: leaving it out of the payload is fine")
    void immutableFieldAbsentIsFine() {
        ValidationResult r = validation.validateImmutability(
                quoteModel(), change("memo", "new memo"), storedApprovedQuote());

        assertTrue(r.getValid(), "untouched immutable fields must not fire: " + r.getErrors());
    }

    @Test
    @DisplayName("immutable field: re-submitting the same value is not a change")
    void immutableFieldSameValueIsNotAChange() {
        // The "read a row, change a few fields, write the whole row back" shape. Without
        // this, every full-row write would trip every lock on the record.
        ValidationResult r = validation.validateImmutability(
                quoteModel(), change("order_no", "Q1001", "memo", "new memo"), storedApprovedQuote());

        assertTrue(r.getValid(), "unchanged round-trip must not trip a lock: " + r.getErrors());
    }

    // ---------- state-conditional immutability ----------

    @Test
    @DisplayName("immutableWhen: price is frozen while the stored status is approved")
    void priceFrozenWhileApproved() {
        ValidationResult r = validation.validateImmutability(
                quoteModel(), change("price", 200), storedApprovedQuote());

        assertFalse(r.getValid());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("Price") && e.contains("approved")),
                "error must name the field and the locking state, got: " + r.getErrors());
    }

    @Test
    @DisplayName("immutableWhen: price is writable while the stored status is draft")
    void priceWritableWhileDraft() {
        Map<String, Object> draft = storedApprovedQuote();
        draft.put("status", "draft");

        ValidationResult r = validation.validateImmutability(quoteModel(), change("price", 200), draft);

        assertTrue(r.getValid(), "draft records must stay editable: " + r.getErrors());
    }

    @Test
    @DisplayName("immutableWhen: the lock only applies to the guarded field")
    void lockDoesNotLeakToOtherFields() {
        ValidationResult r = validation.validateImmutability(
                quoteModel(), change("memo", "new memo"), storedApprovedQuote());

        assertTrue(r.getValid(), "an approved quote may still have its memo edited: " + r.getErrors());
    }

    /**
     * The security-relevant one: the lock is decided by the state the record is ALREADY in,
     * not by whatever the same payload is trying to set the state to. If this ever regresses,
     * a caller unlocks a frozen field simply by flipping the state in the same write.
     */
    @Test
    @DisplayName("immutableWhen: a payload cannot unlock a field by flipping the state in the same write")
    void cannotSelfUnlockInTheSameWrite() {
        ValidationResult r = validation.validateImmutability(
                quoteModel(),
                change("status", "draft", "price", 200),
                storedApprovedQuote());

        assertFalse(r.getValid(), "stored status was approved, so price must still be frozen");
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("Price")),
                "expected the price lock to fire, got: " + r.getErrors());
    }

    // ---------- boundary behaviour ----------

    @Test
    @DisplayName("no existing record means nothing to compare against")
    void nullExistingRecordIsVacuousPass() {
        ValidationResult r = validation.validateImmutability(
                quoteModel(), change("order_no", "Q9999"), null);

        assertTrue(r.getValid(), "create has no prior state to violate: " + r.getErrors());
    }

    @Test
    @DisplayName("empty payload cannot violate anything")
    void emptyPayloadPasses() {
        ValidationResult r = validation.validateImmutability(
                quoteModel(), new HashMap<>(), storedApprovedQuote());

        assertTrue(r.getValid());
    }

    @Test
    @DisplayName("a JDBC type round-trip is not a change")
    void jdbcTypeRoundTripIsNotAChange() {
        // A DATE column reads back as java.sql.Date while the payload carries the string
        // form; an integer column reads back as Integer while JSON supplies Long. Neither
        // is an edit, and neither may trip a lock the caller never touched.
        Map<String, Object> stored = storedApprovedQuote();
        stored.put("signed_on", java.sql.Date.valueOf("2026-07-24"));
        stored.put("price", 100);

        ValidationResult r = validation.validateImmutability(
                quoteModel(),
                change("signed_on", "2026-07-24", "price", 100L),
                stored);

        assertTrue(r.getValid(), "same value in a different wire type is not a change: " + r.getErrors());
    }

    /**
     * An invariant that fails to engage because the declaration says "APPROVED" while the column
     * holds "approved" reads as configured but protects nothing — the inert-guard failure mode.
     * Fail closed instead.
     */
    @Test
    @DisplayName("the locking state matches regardless of case")
    void lockMatchesRegardlessOfCase() {
        Map<String, Object> stored = storedApprovedQuote();
        stored.put("status", "APPROVED");   // declaration says "approved"

        ValidationResult r = validation.validateImmutability(quoteModel(), change("price", 200), stored);

        assertFalse(r.getValid(), "a case mismatch must not silently disarm the lock");
    }

    @Test
    @DisplayName("a field with no immutability metadata is never locked")
    void unannotatedFieldNeverLocks() {
        ValidationResult r = validation.validateImmutability(
                quoteModel(), change("memo", "changed"), storedApprovedQuote());

        assertTrue(r.getValid());
    }

    @Test
    @DisplayName("the locking state field being absent from the stored row leaves the field writable")
    void missingStateLeavesFieldWritable() {
        Map<String, Object> stored = storedApprovedQuote();
        stored.remove("status");

        ValidationResult r = validation.validateImmutability(quoteModel(), change("price", 200), stored);

        assertTrue(r.getValid(), "no state to compare means no lock: " + r.getErrors());
    }
}
