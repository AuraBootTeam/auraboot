package com.auraboot.plugins.crm.engine;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden-standard coverage for {@link DuplicateMatchEngine}:
 * happy path, sad path, edge case, corner case (CRM gap #14).
 */
class DuplicateMatchEngineTest {

    private static Map<String, Object> c(String name, String email, String phone) {
        Map<String, Object> m = new HashMap<>();
        m.put("crm_ct_name", name);
        m.put("crm_ct_email", email);
        m.put("crm_ct_phone", phone);
        return m;
    }

    // ---------- happy path ----------
    @Test
    void allFieldsMatchIsExact() {
        var r = DuplicateMatchEngine.score(
                c("Alice", "alice@x.com", "138-0000-1111"),
                c("Alice", "alice@x.com", "13800001111"));
        assertEquals(100, r.score());
        assertEquals("exact", r.verdict());
    }

    // ---------- sad path ----------
    @Test
    void nothingMatchesIsNone() {
        var r = DuplicateMatchEngine.score(
                c("Alice", "alice@x.com", "111"),
                c("Bob", "bob@y.com", "222"));
        assertEquals(0, r.score());
        assertEquals("none", r.verdict());
    }

    // ---------- edge case ----------
    @Test
    void emailOnlyMatchIsLikely() {
        var r = DuplicateMatchEngine.score(
                c("Alice", "shared@x.com", "111"),
                c("Alicia", "SHARED@x.com", "222"));
        assertEquals(DuplicateMatchEngine.W_EMAIL, r.score());
        assertEquals("likely", r.verdict());
    }

    @Test
    void nameOnlyMatchIsPossible() {
        var r = DuplicateMatchEngine.score(
                c("Acme Corp", "a@x.com", "111"),
                c("Acme Corp", "b@y.com", "222"));
        assertEquals(DuplicateMatchEngine.W_NAME, r.score());
        assertEquals("possible", r.verdict());
    }

    @Test
    void nullRecordIsNone() {
        var r = DuplicateMatchEngine.score(null, c("Alice", "a@x.com", "1"));
        assertEquals(0, r.score());
        assertEquals("none", r.verdict());
    }

    // ---------- corner cases ----------
    @Test
    void blankFieldsDoNotCountAsMatch() {
        // both have empty email/phone -> must not score as matching
        var r = DuplicateMatchEngine.score(c("", "", ""), c("", "", ""));
        assertEquals(0, r.score());
        assertEquals("none", r.verdict());
    }

    @Test
    void namePlusPhoneCrossesLikelyThreshold() {
        // 30 + 20 = 50 -> likely (boundary inclusive)
        var r = DuplicateMatchEngine.score(
                c("Alice", "a@x.com", "13800001111"),
                c("Alice", "b@y.com", "138 0000 1111"));
        assertEquals(50, r.score());
        assertEquals("likely", r.verdict());
    }

    @Test
    void phoneNormalizationMatchesDifferentFormats() {
        var r = DuplicateMatchEngine.score(
                c("X", "a@x.com", "(138) 0000-1111"),
                c("Y", "b@y.com", "13800001111"));
        assertEquals(DuplicateMatchEngine.W_PHONE, r.score());
        assertEquals("possible", r.verdict());
    }
}
