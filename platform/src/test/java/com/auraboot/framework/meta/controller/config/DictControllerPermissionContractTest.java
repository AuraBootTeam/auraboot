package com.auraboot.framework.meta.controller.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Locks the {@link DictController} permission contract: render-time dictionary
 * reads (used to populate form dropdowns) are baseline-readable for any
 * authenticated user, while dictionary management and governance lookups stay
 * gated.
 *
 * <p>Rationale: enum/dict values are application structure (the same class as
 * model/field/page render schema, which is not per-role gated at runtime), not
 * tenant data. Gating them per-role produced "Access forbidden" on every
 * dict-backed form dropdown for non-admin roles. Tenant isolation still applies
 * via the data layer. This test is the regression guard against re-adding a
 * per-role gate onto the runtime read endpoints.
 */
class DictControllerPermissionContractTest {

    /** Render-time reads forms call to populate dropdowns — must NOT be gated. */
    private static final List<String> BASELINE_READ_METHODS = List.of(
            "getDictByCode",        // GET /by-code/{code}
            "loadDictDataByCode",   // GET /by-code/{code}/data  (the dropdown options)
            "batchLoadDictData",    // POST /data/batch          (multi-field forms)
            "getCascadeChildren",   // GET /{pid}/cascade/children (cascading dropdowns)
            "buildCascadeTree",     // GET /{pid}/cascade/tree
            "queryCascadeDict");    // POST /cascade/query

    /** Governance lookups (admin UI) — stay gated with DICT_READ. */
    private static final List<String> GOVERNANCE_READ_METHODS = List.of(
            "getDictByPid",
            "queryDicts",
            "loadDictData",         // GET /{pid}/data (by pid)
            "getDictVersionHistory");

    /** Mutating endpoints — stay gated with DICT_MANAGE. */
    private static final List<String> MANAGE_METHODS = List.of(
            "createDict",
            "updateDict",
            "replaceDictItems",
            "deleteDict",
            "publishDict",
            "unpublishDict",
            "importDict");

    @Test
    @DisplayName("render-time dict reads are baseline (no @RequirePermission)")
    void renderTimeReadsAreBaseline() {
        for (String name : BASELINE_READ_METHODS) {
            Method m = method(name);
            assertThat(m.isAnnotationPresent(RequirePermission.class))
                    .as("%s must be baseline-readable (no @RequirePermission) so form dropdowns work", name)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("governance dict reads stay gated with DICT_READ")
    void governanceReadsStayGated() {
        for (String name : GOVERNANCE_READ_METHODS) {
            Method m = method(name);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            assertThat(ann).as("%s must keep @RequirePermission", name).isNotNull();
            assertThat(ann.value())
                    .as("%s must require DICT_READ", name)
                    .isEqualTo(MetaPermission.DICT_READ);
        }
    }

    @Test
    @DisplayName("dict management stays gated with DICT_MANAGE")
    void manageStaysGated() {
        for (String name : MANAGE_METHODS) {
            Method m = method(name);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            assertThat(ann).as("%s must keep @RequirePermission", name).isNotNull();
            assertThat(ann.value())
                    .as("%s must require DICT_MANAGE", name)
                    .isEqualTo(MetaPermission.DICT_MANAGE);
        }
    }

    private static Method method(String name) {
        return Arrays.stream(DictController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("DictController has no method " + name
                        + " — update DictControllerPermissionContractTest if it was renamed/removed"));
    }
}
