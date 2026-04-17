package com.auraboot.framework.meta.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCapabilitiesTest {

    @Test
    void empty_has_all_flags_false_and_empty_whitelists() {
        ModelCapabilities caps = ModelCapabilities.empty();
        assertThat(caps.isList()).isFalse();
        assertThat(caps.isDetail()).isFalse();
        assertThat(caps.isCreate()).isFalse();
        assertThat(caps.isUpdate()).isFalse();
        assertThat(caps.isDelete()).isFalse();
        assertThat(caps.isBulkDelete()).isFalse();
        assertThat(caps.isExport()).isFalse();
        assertThat(caps.isSort()).isFalse();
        assertThat(caps.isFilter()).isFalse();
        assertThat(caps.isPaginate()).isFalse();
        assertThat(caps.getSortableFields()).isEmpty();
        assertThat(caps.getFilterableFields()).isEmpty();
        assertThat(caps.getDetailKeyField()).isNull();
    }

    @Test
    void fullPhysical_enables_full_crud_and_query() {
        ModelCapabilities caps = ModelCapabilities.fullPhysical();
        assertThat(caps.isList()).isTrue();
        assertThat(caps.isDetail()).isTrue();
        assertThat(caps.isCreate()).isTrue();
        assertThat(caps.isUpdate()).isTrue();
        assertThat(caps.isDelete()).isTrue();
        assertThat(caps.isBulkDelete()).isTrue();
        assertThat(caps.isExport()).isTrue();
        assertThat(caps.isSort()).isTrue();
        assertThat(caps.isFilter()).isTrue();
        assertThat(caps.isPaginate()).isTrue();
    }

    @Test
    void virtualReadOnly_disables_writes_but_keeps_query() {
        ModelCapabilities caps = ModelCapabilities.virtualReadOnly();
        assertThat(caps.isList()).isTrue();
        assertThat(caps.isDetail()).isTrue();
        assertThat(caps.isExport()).isTrue();
        assertThat(caps.isSort()).isTrue();
        assertThat(caps.isFilter()).isTrue();
        assertThat(caps.isPaginate()).isTrue();
        assertThat(caps.isCreate()).isFalse();
        assertThat(caps.isUpdate()).isFalse();
        assertThat(caps.isDelete()).isFalse();
        assertThat(caps.isBulkDelete()).isFalse();
    }

    @Test
    void canSortBy_requires_sort_flag_and_whitelist_membership() {
        ModelCapabilities caps = ModelCapabilities.builder()
            .sort(true)
            .sortableFields(List.of("created_at", "total_amount"))
            .build();
        assertThat(caps.canSortBy("created_at")).isTrue();
        assertThat(caps.canSortBy("total_amount")).isTrue();
        assertThat(caps.canSortBy("unknown_field")).isFalse();
    }

    @Test
    void canSortBy_is_case_sensitive() {
        ModelCapabilities caps = ModelCapabilities.builder()
            .sort(true)
            .sortableFields(List.of("created_at"))
            .build();
        assertThat(caps.canSortBy("CREATED_AT")).isFalse();
        assertThat(caps.canSortBy("Created_At")).isFalse();
    }

    @Test
    void canSortBy_returns_false_when_sort_flag_disabled_even_if_whitelisted() {
        ModelCapabilities caps = ModelCapabilities.builder()
            .sort(false)
            .sortableFields(List.of("created_at"))
            .build();
        assertThat(caps.canSortBy("created_at")).isFalse();
    }

    @Test
    void canFilterBy_respects_flag_and_whitelist() {
        ModelCapabilities caps = ModelCapabilities.builder()
            .filter(true)
            .filterableFields(List.of("status"))
            .build();
        assertThat(caps.canFilterBy("status")).isTrue();
        assertThat(caps.canFilterBy("name")).isFalse();
    }

    @Test
    void resolveDetailKeyField_defaults_to_primaryKey_when_absent() {
        ModelCapabilities caps = ModelCapabilities.builder().detail(true).build();
        assertThat(caps.resolveDetailKeyField("id")).isEqualTo("id");
    }

    @Test
    void resolveDetailKeyField_defaults_to_primaryKey_when_blank() {
        ModelCapabilities caps = ModelCapabilities.builder()
            .detail(true)
            .detailKeyField("   ")
            .build();
        assertThat(caps.resolveDetailKeyField("id")).isEqualTo("id");
    }

    @Test
    void resolveDetailKeyField_uses_explicit_when_present() {
        ModelCapabilities caps = ModelCapabilities.builder()
            .detail(true)
            .detailKeyField("uuid")
            .build();
        assertThat(caps.resolveDetailKeyField("id")).isEqualTo("uuid");
    }
}
