package com.auraboot.framework.meta.mapper;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicSqlProviderRowVersionLockTest {

    @Test
    void buildsTenantScopedBoundExclusiveLock() {
        assertThat(DynamicSqlProvider.selectRowVersionForUpdate(Map.of(
                "tableName", "am_asset",
                "pkColumn", "pid")))
                .isEqualTo("SELECT row_version FROM am_asset"
                        + " WHERE tenant_id = #{tenantId}"
                        + " AND pid = #{recordId} FOR UPDATE");
    }

    @Test
    void rejectsUntrustedIdentifiers() {
        assertThatThrownBy(() -> DynamicSqlProvider.selectRowVersionForUpdate(Map.of(
                "tableName", "am_asset; DROP TABLE am_asset",
                "pkColumn", "pid")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DynamicSqlProvider.selectRowVersionForUpdate(Map.of(
                "tableName", "am_asset",
                "pkColumn", "pid OR true")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
