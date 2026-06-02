package com.auraboot.framework.i18n.service;

import com.auraboot.framework.i18n.service.I18nOverrideAuditor.OverrideAuditEntry;
import com.auraboot.framework.i18n.service.I18nOverrideAuditor.OverrideAuditReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link I18nOverrideAuditor}'s pure classification logic.
 * Exercises {@code classify(...)} directly with hand-built layer maps so no
 * DB / classpath wiring is required.
 */
class I18nOverrideAuditorTest {

    private final I18nOverrideAuditor auditor = new I18nOverrideAuditor(null, null);

    private OverrideAuditEntry entryFor(OverrideAuditReport report, String key) {
        return report.entries().stream()
            .filter(e -> e.key().equals(key))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no entry for key " + key));
    }

    @Test
    void yamlOnlyKey_winnerIsYaml_andNotFlagged() {
        OverrideAuditReport r = auditor.classify("zh-CN",
            Map.of("a.only", "y"), Map.of(), Map.of(), Map.of(), Map.of());

        OverrideAuditEntry e = entryFor(r, "a.only");
        assertThat(e.winnerLayer()).isEqualTo(I18nOverrideAuditor.LAYER_YAML);
        assertThat(e.winnerValue()).isEqualTo("y");
        assertThat(e.classification()).isNull(); // single layer, nothing notable
        assertThat(r.driftCount()).isZero();
        assertThat(r.overriddenCount()).isZero();
    }

    @Test
    void dbOverridesJsonWithDifferentValue_winnerIsDb_andOverridden() {
        OverrideAuditReport r = auditor.classify("zh-CN",
            Map.of(), Map.of("a.k", "json-val"), Map.of("a.k", "db-val"), Map.of(), Map.of());

        OverrideAuditEntry e = entryFor(r, "a.k");
        assertThat(e.winnerLayer()).isEqualTo(I18nOverrideAuditor.LAYER_DB);
        assertThat(e.winnerValue()).isEqualTo("db-val");
        assertThat(e.classification()).isEqualTo(I18nOverrideAuditor.CLASS_OVERRIDDEN);
        assertThat(r.overriddenCount()).isEqualTo(1);
        assertThat(r.driftCount()).isZero();
    }

    @Test
    void seedDiffersFromDb_onSystemKey_isDrift() {
        OverrideAuditReport r = auditor.classify("zh-CN",
            Map.of(), Map.of(),
            Map.of("auth.headline.pre", "old"),      // DB stale
            Map.of("auth.headline.pre", "new"),      // seed updated
            Map.of("auth.headline.pre", "system"));  // platform-owned, un-edited

        OverrideAuditEntry e = entryFor(r, "auth.headline.pre");
        assertThat(e.classification()).isEqualTo(I18nOverrideAuditor.CLASS_SEED_DRIFT);
        assertThat(r.driftCount()).isEqualTo(1);
    }

    @Test
    void seedDiffersFromDb_butNotSystemKey_isNotDrift() {
        // Same value mismatch, but the DB row is NOT a platform-owned/un-edited
        // system key (absent from dbSystemKeys) — must NOT be flagged as drift.
        OverrideAuditReport r = auditor.classify("zh-CN",
            Map.of(), Map.of(),
            Map.of("auth.headline.pre", "old"),
            Map.of("auth.headline.pre", "new"),
            Map.of()); // not a system/un-edited key

        OverrideAuditEntry e = entryFor(r, "auth.headline.pre");
        assertThat(e.classification()).isNotEqualTo(I18nOverrideAuditor.CLASS_SEED_DRIFT);
        assertThat(r.driftCount()).isZero();
    }

    @Test
    void seedEqualsDb_isConsistentNotDrift() {
        OverrideAuditReport r = auditor.classify("zh-CN",
            Map.of(), Map.of("a.k", "same"), Map.of("a.k", "same"),
            Map.of("a.k", "same"), Map.of("a.k", "system"));

        OverrideAuditEntry e = entryFor(r, "a.k");
        assertThat(e.classification()).isEqualTo(I18nOverrideAuditor.CLASS_CONSISTENT);
        assertThat(r.driftCount()).isZero();
        assertThat(r.overriddenCount()).isZero();
    }

    @Test
    void multiLayerEqualValues_isConsistent() {
        OverrideAuditReport r = auditor.classify("zh-CN",
            Map.of("a.k", "v"), Map.of("a.k", "v"), Map.of("a.k", "v"),
            Map.of(), Map.of());

        OverrideAuditEntry e = entryFor(r, "a.k");
        assertThat(e.classification()).isEqualTo(I18nOverrideAuditor.CLASS_CONSISTENT);
        assertThat(r.overriddenCount()).isZero();
    }

    @Test
    void report_totalsCountAllDistinctKeys() {
        OverrideAuditReport r = auditor.classify("zh-CN",
            Map.of("a", "1"), Map.of("b", "2"), Map.of("c", "3"),
            Map.of(), Map.of());
        assertThat(r.totalKeys()).isEqualTo(3);
        List<String> keys = r.entries().stream().map(OverrideAuditEntry::key).toList();
        assertThat(keys).containsExactlyInAnyOrder("a", "b", "c");
    }
}
