package com.auraboot.framework.decision.runtime;

import com.auraboot.framework.decision.entity.DrtVersionEntity;
import com.auraboot.framework.decision.model.VersionBinding;
import com.auraboot.framework.decision.model.VersionStatus;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pure resolution of which {@link DrtVersionEntity} a consumer binds to, given a
 * {@link VersionBinding} strategy (docs/1.md §13.7). Operates on the full candidate list so it is
 * unit-testable without a database. Returns {@code null} when no version satisfies the binding.
 *
 * <ul>
 *   <li>LATEST — highest-numbered PUBLISHED version</li>
 *   <li>FIXED_VERSION / DEPLOYMENT_VERSION — the pinned version number (must be bindable)</li>
 *   <li>VERSION_TAG — highest PUBLISHED version carrying the tag</li>
 *   <li>EFFECTIVE_TIME / AS_OF_EVENT_TIME — the PUBLISHED version effective at the given instant
 *       ({@code effectiveFrom <= at < effectiveTo}), preferring the highest version</li>
 * </ul>
 */
public final class VersionSelector {

    /** Inputs for the bindings that need them (fixed version / tag / instant). */
    public record Criteria(Integer fixedVersion, String versionTag, Instant asOf) {
        public static Criteria of(Integer fixedVersion, String versionTag, Instant asOf) {
            return new Criteria(fixedVersion, versionTag, asOf);
        }
    }

    private VersionSelector() {}

    public static DrtVersionEntity select(List<DrtVersionEntity> all, VersionBinding binding, Criteria criteria) {
        if (all == null || all.isEmpty()) {
            return null;
        }
        VersionBinding b = binding != null ? binding : VersionBinding.LATEST;
        return switch (b) {
            case LATEST -> highest(all, v -> isStatus(v, VersionStatus.PUBLISHED));
            case FIXED_VERSION, DEPLOYMENT_VERSION -> {
                if (criteria == null || criteria.fixedVersion() == null) {
                    yield null;
                }
                yield all.stream()
                        .filter(v -> Objects.equals(v.getVersion(), criteria.fixedVersion()))
                        .filter(VersionSelector::isBindable)
                        .findFirst().orElse(null);
            }
            case VERSION_TAG -> {
                if (criteria == null || criteria.versionTag() == null) {
                    yield null;
                }
                yield highest(all, v -> isStatus(v, VersionStatus.PUBLISHED)
                        && criteria.versionTag().equals(v.getVersionTag()));
            }
            case EFFECTIVE_TIME, AS_OF_EVENT_TIME -> {
                if (criteria == null || criteria.asOf() == null) {
                    yield null;
                }
                Instant at = criteria.asOf();
                yield highest(all, v -> isStatus(v, VersionStatus.PUBLISHED) && effectiveAt(v, at));
            }
            case ROLLOUT -> null;
        };
    }

    private static DrtVersionEntity highest(List<DrtVersionEntity> all, java.util.function.Predicate<DrtVersionEntity> filter) {
        return all.stream()
                .filter(filter)
                .max(Comparator.comparingInt(v -> v.getVersion() == null ? Integer.MIN_VALUE : v.getVersion()))
                .orElse(null);
    }

    private static boolean effectiveAt(DrtVersionEntity v, Instant at) {
        Instant from = v.getEffectiveFrom();
        Instant to = v.getEffectiveTo();
        if (from != null && from.isAfter(at)) {
            return false;
        }
        return to == null || at.isBefore(to);
    }

    private static boolean isStatus(DrtVersionEntity v, VersionStatus status) {
        return v.getStatus() != null && status.name().equals(v.getStatus());
    }

    private static boolean isBindable(DrtVersionEntity v) {
        try {
            return v.getStatus() != null && VersionStatus.valueOf(v.getStatus()).isBindable();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
