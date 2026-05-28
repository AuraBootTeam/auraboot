package com.auraboot.framework.connector.cdc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SyncStrategy}.
 */
class SyncStrategyTest {

    @Test
    void enum_has_four_values_in_documented_order() {
        assertThat(SyncStrategy.values())
                .containsExactly(
                        SyncStrategy.FULL_REFRESH,
                        SyncStrategy.INCREMENTAL_APPEND,
                        SyncStrategy.INCREMENTAL_DEDUP,
                        SyncStrategy.CDC);
    }

    @Test
    void yamlValue_returns_kebab_case_alias() {
        assertThat(SyncStrategy.FULL_REFRESH.yamlValue()).isEqualTo("full-refresh");
        assertThat(SyncStrategy.INCREMENTAL_APPEND.yamlValue()).isEqualTo("incremental-append");
        assertThat(SyncStrategy.INCREMENTAL_DEDUP.yamlValue()).isEqualTo("incremental-dedup");
        assertThat(SyncStrategy.CDC.yamlValue()).isEqualTo("cdc");
    }

    @Test
    void fromYaml_parses_kebab_and_enum_name_case_insensitive() {
        assertThat(SyncStrategy.fromYaml("cdc")).isEqualTo(SyncStrategy.CDC);
        assertThat(SyncStrategy.fromYaml("CDC")).isEqualTo(SyncStrategy.CDC);
        assertThat(SyncStrategy.fromYaml("incremental-append")).isEqualTo(SyncStrategy.INCREMENTAL_APPEND);
        assertThat(SyncStrategy.fromYaml("INCREMENTAL_APPEND")).isEqualTo(SyncStrategy.INCREMENTAL_APPEND);
        assertThat(SyncStrategy.fromYaml(" full-refresh ")).isEqualTo(SyncStrategy.FULL_REFRESH);

        assertThatThrownBy(() -> SyncStrategy.fromYaml(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SyncStrategy.fromYaml(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SyncStrategy.fromYaml("bogus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bogus");
    }
}
