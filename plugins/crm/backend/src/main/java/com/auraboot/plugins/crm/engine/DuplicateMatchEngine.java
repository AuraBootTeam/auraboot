package com.auraboot.plugins.crm.engine;

import java.util.Map;

/**
 * Pure duplicate-match scoring for contacts (CRM gap #14).
 *
 * <p>Scores how likely two contact records are the same person, by exact match
 * on weighted key fields (email 50, name 30, phone 20 → 0..100), and returns a
 * verdict band. Separated from the handler so the rules are unit-testable without
 * a Spring context or database.
 *
 * <p>Verdict: score &gt;= 80 exact; 50..79 likely; 20..49 possible; &lt; 20 none.
 * Email comparison is case-insensitive; phone strips non-digits.
 */
public final class DuplicateMatchEngine {

    private DuplicateMatchEngine() {
    }

    public static final int W_EMAIL = 50;
    public static final int W_NAME = 30;
    public static final int W_PHONE = 20;

    public record Result(int score, String verdict) {
    }

    public static Result score(Map<String, Object> a, Map<String, Object> b) {
        if (a == null || b == null) {
            return new Result(0, "none");
        }
        int score = 0;
        if (emailEq(get(a, "crm_ct_email"), get(b, "crm_ct_email"))) {
            score += W_EMAIL;
        }
        if (nonBlankEq(get(a, "crm_ct_name"), get(b, "crm_ct_name"))) {
            score += W_NAME;
        }
        if (phoneEq(get(a, "crm_ct_phone"), get(b, "crm_ct_phone"))) {
            score += W_PHONE;
        }
        return new Result(score, verdict(score));
    }

    public static String verdict(int score) {
        if (score >= 80) {
            return "exact";
        }
        if (score >= 50) {
            return "likely";
        }
        if (score >= 20) {
            return "possible";
        }
        return "none";
    }

    private static String get(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString().trim();
    }

    private static boolean nonBlankEq(String a, String b) {
        return !a.isEmpty() && a.equals(b);
    }

    private static boolean emailEq(String a, String b) {
        return !a.isEmpty() && a.equalsIgnoreCase(b);
    }

    private static boolean phoneEq(String a, String b) {
        String da = a.replaceAll("\\D", "");
        String db = b.replaceAll("\\D", "");
        return !da.isEmpty() && da.equals(db);
    }
}
