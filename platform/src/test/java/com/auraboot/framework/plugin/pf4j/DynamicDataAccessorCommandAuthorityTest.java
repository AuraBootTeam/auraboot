package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The accessor's half of DDR-2026-07-22: a handler's data access stops being re-projected through
 * the caller's record-level read permission, but ONLY while the command boundary's authority is
 * open, and never a microsecond longer.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DataAccessor honours the command boundary's authority")
class DynamicDataAccessorCommandAuthorityTest {

    @Mock private DynamicDataService dynamicDataService;

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("inside the scope, a handler's write is not re-projected through the caller")
    void insideTheScopeTheProjectionIsLifted() {
        AtomicBoolean bypassedDuringCall = new AtomicBoolean(false);
        when(dynamicDataService.update(anyString(), anyString(), any())).thenAnswer(i -> {
            bypassedDuringCall.set(MetaContext.isDataPermissionBypassed());
            return Map.of("pid", "REC-1");
        });
        DynamicDataAccessorImpl accessor = new DynamicDataAccessorImpl(dynamicDataService);

        MetaContext.runWithCommandAuthority("qo.price.manage",
                () -> accessor.update("qo_price_evidence_common", "REC-1", Map.of("qo_pe_status", "captured")));

        assertThat(bypassedDuringCall).isTrue();
    }

    /**
     * Without an authority there must be no change whatsoever — this is what makes the ~200 commands
     * that declare no permissions safe: they never open a scope, so they never reach this branch.
     */
    @Test
    @DisplayName("with no authority open, the caller's projection still applies")
    void withoutAuthorityNothingChanges() {
        AtomicBoolean bypassedDuringCall = new AtomicBoolean(true);
        when(dynamicDataService.update(anyString(), anyString(), any())).thenAnswer(i -> {
            bypassedDuringCall.set(MetaContext.isDataPermissionBypassed());
            return Map.of("pid", "REC-1");
        });
        DynamicDataAccessorImpl accessor = new DynamicDataAccessorImpl(dynamicDataService);

        accessor.update("qo_price_evidence_common", "REC-1", Map.of("qo_pe_status", "captured"));

        assertThat(bypassedDuringCall).isFalse();
    }

    /**
     * Async handlers run on a POOLED thread. A scope that outlives its command would hand the next,
     * entirely unrelated task a standing exemption — the worst possible failure mode here, and a
     * silent one.
     */
    @Test
    @DisplayName("the scope does not outlive the command, so the next task on this thread is unaffected")
    void theScopeDoesNotLeakToTheNextTaskOnTheSameThread() {
        AtomicBoolean bypassedInSecondTask = new AtomicBoolean(true);
        when(dynamicDataService.update(anyString(), anyString(), any())).thenReturn(Map.of("pid", "REC-1"));
        when(dynamicDataService.getById(anyString(), anyString())).thenAnswer(i -> {
            bypassedInSecondTask.set(MetaContext.isDataPermissionBypassed());
            return Map.of("pid", "REC-2");
        });
        DynamicDataAccessorImpl accessor = new DynamicDataAccessorImpl(dynamicDataService);

        MetaContext.runWithCommandAuthority("qo.price.manage",
                () -> accessor.update("qo_price_evidence_common", "REC-1", Map.of()));
        // same thread, no authority: an unrelated task picked up by the pool
        accessor.getById("qo_quote_common", "REC-2");

        assertThat(MetaContext.hasCommandAuthority()).isFalse();
        assertThat(bypassedInSecondTask)
                .as("an unrelated task must not inherit a previous command's authority")
                .isFalse();
    }

    @Test
    @DisplayName("a handler that throws still closes the scope")
    void aFailingHandlerStillClosesTheScope() {
        assertThatThrownBy(() -> MetaContext.runWithCommandAuthority("qo.price.manage", () -> {
            throw new IllegalStateException("handler blew up");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(MetaContext.hasCommandAuthority()).isFalse();
        assertThat(MetaContext.isDataPermissionBypassed()).isFalse();
    }

    /** A nested command must restore the outer authority, not clear it. */
    @Test
    @DisplayName("a nested scope restores the outer authority rather than dropping it")
    void aNestedScopeRestoresTheOuterAuthority() {
        MetaContext.runWithCommandAuthority("outer.permission", () -> {
            MetaContext.runWithCommandAuthority("inner.permission",
                    () -> assertThat(MetaContext.getCommandAuthority()).isEqualTo("inner.permission"));
            assertThat(MetaContext.getCommandAuthority()).isEqualTo("outer.permission");
        });

        assertThat(MetaContext.hasCommandAuthority()).isFalse();
    }

    /**
     * An authority with no permission behind it cannot be opened at all.
     *
     * <p>This is what makes "only AUTHORIZED opens a scope" a guarantee rather than a coincidence.
     * A NOT_APPLICABLE verdict carries a null code, so a bug that opened a scope for one would look
     * harmless — nothing would read as authorized — while encoding precisely the mistake this
     * design exists to prevent. Mutation testing found it exactly this way: weakening the caller's
     * check left every test green.
     */
    @Test
    @DisplayName("a scope cannot be opened without naming the permission that granted it")
    void aScopeCannotBeOpenedWithoutAPermission() {
        assertThatThrownBy(() -> MetaContext.runWithCommandAuthority(null, () -> "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must name the permission");
        assertThatThrownBy(() -> MetaContext.runWithCommandAuthority("  ", () -> "x"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(MetaContext.hasCommandAuthority()).isFalse();
    }

    /**
     * Tenant is an identity partition, not a permission. It must survive the scope untouched — the
     * scope lifts the caller's read projection and nothing else.
     */
    @Test
    @DisplayName("the tenant partition is untouched inside the scope")
    void theTenantPartitionSurvivesTheScope() {
        MetaContext.setContext(77L, 42L, "u-42", "tester");

        MetaContext.runWithCommandAuthority("qo.price.manage", () -> {
            assertThat(MetaContext.getCurrentTenantId()).isEqualTo(77L);
            assertThat(MetaContext.getCurrentUserId()).isEqualTo(42L);
        });

        assertThat(MetaContext.getCurrentTenantId()).isEqualTo(77L);
    }
}
