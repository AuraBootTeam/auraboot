package com.auraboot.framework.meta.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When the permit plan resolved the caller has ALL (unrestricted) write access, the data layer
 * honours that one boundary decision and skips the per-site policy lookup (authz §11.15, §11.10 —
 * the plan becomes authoritative for the unrestricted case). Any other grade — {@code null} (no
 * plan), {@code SELF}, or anything unexpected — defers to the engine, the fail-closed direction so a
 * missing or unknown grade can never skip a filter.
 */
class WriteRowFilterScopeTest {

    @Test
    @DisplayName("an explicit ALL grade grants unrestricted write (skips the engine filter)")
    void allGradeGrantsUnrestrictedWrite() {
        assertThat(DynamicDataServiceImpl.planGrantsUnrestrictedWrite("ALL")).isTrue();
    }

    @Test
    @DisplayName("SELF does not grant unrestricted write — it defers to the engine")
    void selfGradeDefersToEngine() {
        assertThat(DynamicDataServiceImpl.planGrantsUnrestrictedWrite("SELF")).isFalse();
    }

    @Test
    @DisplayName("no plan and unexpected grades fail closed — they defer to the engine")
    void noPlanAndUnexpectedGradesFailClosed() {
        assertThat(DynamicDataServiceImpl.planGrantsUnrestrictedWrite(null)).isFalse();
        assertThat(DynamicDataServiceImpl.planGrantsUnrestrictedWrite("DEPARTMENT")).isFalse();
        assertThat(DynamicDataServiceImpl.planGrantsUnrestrictedWrite("")).isFalse();
    }
}
