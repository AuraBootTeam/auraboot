package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicDataQueryScopeTest {

    @Test
    void permissionMetadataIsReusedOnlyInsideTheCurrentScope() {
        AtomicInteger loads = new AtomicInteger();

        try (DynamicDataQueryScope ignored = DynamicDataQueryScope.open()) {
            assertThat(DynamicDataQueryScope.nonWritableFields(
                    1L, "order", 2L, () -> fields(loads))).containsExactly("price");
            assertThat(DynamicDataQueryScope.nonWritableFields(
                    1L, "order", 2L, () -> fields(loads))).containsExactly("price");
        }

        try (DynamicDataQueryScope ignored = DynamicDataQueryScope.open()) {
            DynamicDataQueryScope.nonWritableFields(1L, "order", 2L, () -> fields(loads));
        }

        assertThat(loads).hasValue(2);
    }

    @Test
    void permissionMetadataKeysIncludeTenantModelAndSubject() {
        AtomicInteger loads = new AtomicInteger();

        try (DynamicDataQueryScope ignored = DynamicDataQueryScope.open()) {
            DynamicDataQueryScope.fieldPermissions(1L, "order", 2L, () -> permissions(loads));
            DynamicDataQueryScope.fieldPermissions(2L, "order", 2L, () -> permissions(loads));
            DynamicDataQueryScope.fieldPermissions(1L, "quote", 2L, () -> permissions(loads));
            DynamicDataQueryScope.fieldPermissions(1L, "order", 3L, () -> permissions(loads));
        }

        assertThat(loads).hasValue(4);
    }

    @Test
    void nestedScopesShareValuesUntilTheOuterScopeCloses() {
        AtomicInteger loads = new AtomicInteger();

        try (DynamicDataQueryScope outer = DynamicDataQueryScope.open()) {
            DynamicDataQueryScope.nonWritableFields(1L, "order", 2L, () -> fields(loads));
            try (DynamicDataQueryScope inner = DynamicDataQueryScope.open()) {
                DynamicDataQueryScope.nonWritableFields(1L, "order", 2L, () -> fields(loads));
            }
            DynamicDataQueryScope.nonWritableFields(1L, "order", 2L, () -> fields(loads));
        }

        assertThat(loads).hasValue(1);
        assertThat(DynamicDataQueryScope.isActive()).isFalse();
    }

    @Test
    void mutationInvalidatesAllTransitiveRowAndDomainPredicatesWithoutDroppingPermissionMetadata() {
        AtomicInteger rowLoads=new AtomicInteger(),domainLoads=new AtomicInteger(),metadataLoads=new AtomicInteger();
        try (DynamicDataQueryScope outer=DynamicDataQueryScope.open()) {
            assertThat(DynamicDataQueryScope.rowFilter(1L,"bundle",2L,()->"AND version = "+rowLoads.incrementAndGet())).isEqualTo("AND version = 1");
            assertThat(DynamicDataQueryScope.domainFilter(1L,"other_model",2L,()->"AND version = "+domainLoads.incrementAndGet())).isEqualTo("AND version = 1");
            DynamicDataQueryScope.nonWritableFields(1L,"bundle",2L,()->fields(metadataLoads));
            try (DynamicDataQueryScope inner=DynamicDataQueryScope.open()) {
                DynamicDataQueryScope.invalidateFiltersAfterMutation();
            }
            assertThat(DynamicDataQueryScope.rowFilter(1L,"bundle",2L,()->"AND version = "+rowLoads.incrementAndGet())).isEqualTo("AND version = 2");
            assertThat(DynamicDataQueryScope.domainFilter(1L,"other_model",2L,()->"AND version = "+domainLoads.incrementAndGet())).isEqualTo("AND version = 2");
            DynamicDataQueryScope.nonWritableFields(1L,"bundle",2L,()->fields(metadataLoads));
            assertThat(metadataLoads).hasValue(1);
        }
        DynamicDataQueryScope.invalidateFiltersAfterMutation();
        assertThat(DynamicDataQueryScope.isActive()).isFalse();
    }

    private static Set<String> fields(AtomicInteger loads) {
        loads.incrementAndGet();
        return Set.of("price");
    }

    private static FieldPermissionSet permissions(AtomicInteger loads) {
        loads.incrementAndGet();
        return FieldPermissionSet.allAllowed(Set.of("price"));
    }
}
