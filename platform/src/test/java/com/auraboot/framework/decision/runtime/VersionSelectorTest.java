package com.auraboot.framework.decision.runtime;

import com.auraboot.framework.decision.entity.DrtVersionEntity;
import com.auraboot.framework.decision.model.VersionBinding;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** VersionSelector — pure resolution of the 6 binding strategies (docs/1.md §13.7), no DB. */
class VersionSelectorTest {

    private DrtVersionEntity ver(int version, String status, String tag, Instant from, Instant to) {
        DrtVersionEntity v = new DrtVersionEntity();
        v.setVersion(version);
        v.setStatus(status);
        v.setVersionTag(tag);
        v.setEffectiveFrom(from);
        v.setEffectiveTo(to);
        return v;
    }

    private VersionSelector.Criteria crit(Integer fixed, String tag, Instant asOf) {
        return VersionSelector.Criteria.of(fixed, tag, asOf);
    }

    @Test
    void latestPicksHighestPublished_ignoresDraft() {
        List<DrtVersionEntity> all = List.of(
                ver(1, "PUBLISHED", null, null, null),
                ver(2, "PUBLISHED", null, null, null),
                ver(3, "DRAFT", null, null, null));
        DrtVersionEntity sel = VersionSelector.select(all, VersionBinding.LATEST, null);
        assertThat(sel.getVersion()).isEqualTo(2); // 3 is DRAFT, not bindable
    }

    @Test
    void fixedVersionPicksExact_andRespectsBindable() {
        List<DrtVersionEntity> all = List.of(
                ver(1, "PUBLISHED", null, null, null),
                ver(2, "PUBLISHED", null, null, null));
        assertThat(VersionSelector.select(all, VersionBinding.FIXED_VERSION, crit(1, null, null)).getVersion()).isEqualTo(1);
        // missing criteria -> null
        assertThat(VersionSelector.select(all, VersionBinding.FIXED_VERSION, crit(null, null, null))).isNull();
        // a DRAFT fixed version is not bindable -> null
        List<DrtVersionEntity> withDraft = List.of(ver(5, "DRAFT", null, null, null));
        assertThat(VersionSelector.select(withDraft, VersionBinding.FIXED_VERSION, crit(5, null, null))).isNull();
    }

    @Test
    void deploymentVersionBehavesLikeFixed() {
        List<DrtVersionEntity> all = List.of(ver(3, "PUBLISHED", null, null, null));
        assertThat(VersionSelector.select(all, VersionBinding.DEPLOYMENT_VERSION, crit(3, null, null)).getVersion()).isEqualTo(3);
    }

    @Test
    void versionTagPicksHighestPublishedWithTag() {
        List<DrtVersionEntity> all = List.of(
                ver(1, "PUBLISHED", "v2026.06", null, null),
                ver(2, "PUBLISHED", "v2026.06", null, null),
                ver(3, "PUBLISHED", "v2026.07", null, null));
        DrtVersionEntity sel = VersionSelector.select(all, VersionBinding.VERSION_TAG, crit(null, "v2026.06", null));
        assertThat(sel.getVersion()).isEqualTo(2);
        assertThat(VersionSelector.select(all, VersionBinding.VERSION_TAG, crit(null, "missing", null))).isNull();
    }

    @Test
    void effectiveTimePicksVersionEffectiveAtInstant() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-06-01T00:00:00Z");
        List<DrtVersionEntity> all = List.of(
                ver(1, "PUBLISHED", null, t0, t2),     // effective [t0, t2)
                ver(2, "PUBLISHED", null, t2, null));  // effective [t2, ∞)
        Instant t1 = Instant.parse("2026-03-01T00:00:00Z");
        Instant t3 = Instant.parse("2026-09-01T00:00:00Z");
        assertThat(VersionSelector.select(all, VersionBinding.EFFECTIVE_TIME, crit(null, null, t1)).getVersion()).isEqualTo(1);
        assertThat(VersionSelector.select(all, VersionBinding.AS_OF_EVENT_TIME, crit(null, null, t3)).getVersion()).isEqualTo(2);
        // before any effectiveFrom -> none
        Instant before = Instant.parse("2025-12-01T00:00:00Z");
        assertThat(VersionSelector.select(all, VersionBinding.EFFECTIVE_TIME, crit(null, null, before))).isNull();
    }

    @Test
    void emptyOrNullCandidatesYieldNull() {
        assertThat(VersionSelector.select(List.of(), VersionBinding.LATEST, null)).isNull();
        assertThat(VersionSelector.select(null, VersionBinding.LATEST, null)).isNull();
    }
}
