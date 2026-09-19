package com.auraboot.framework.meta.cache;

import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetaCacheKeyGeneratorTest {

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void dataAccessContextSuffixSeparatesUsersAndMembers() {
        MetaContext.setContext(10L, 20L, "user-a", "user-a");
        MetaContext.setMemberId(30L);
        String first = MetaCacheKeyGenerator.getDataAccessContextSuffix();

        MetaContext.setContext(10L, 21L, "user-b", "user-b");
        MetaContext.setMemberId(31L);
        String second = MetaCacheKeyGenerator.getDataAccessContextSuffix();

        assertThat(first).startsWith("10:20:30:scoped:e");
        assertThat(second).startsWith("10:21:31:scoped:e");
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void dataAccessContextSuffixOrphansEntriesOnEpochBump() {
        MetaContext.setContext(10L, 20L, "user-a", "user-a");
        MetaContext.setMemberId(30L);
        String before = MetaCacheKeyGenerator.getDataAccessContextSuffix();

        DataAccessCacheEpoch.bump();
        String after = MetaCacheKeyGenerator.getDataAccessContextSuffix();

        assertThat(after).isNotEqualTo(before);
        assertThat(Long.parseLong(after.substring(after.lastIndexOf(":e") + 2)))
                .isEqualTo(DataAccessCacheEpoch.current());
    }

    @Test
    void dataAccessContextSuffixSeparatesPermitGrades() {
        MetaContext.setContext(10L, 20L, "user-a", "user-a");
        MetaContext.setMemberId(30L);
        String scoped = MetaCacheKeyGenerator.getDataAccessContextSuffix();

        String all = MetaContext.runWithCommandPermitScope("ALL",
                MetaCacheKeyGenerator::getDataAccessContextSuffix);
        String self = MetaContext.runWithCommandPermitScope("SELF",
                MetaCacheKeyGenerator::getDataAccessContextSuffix);

        assertThat(scoped).startsWith("10:20:30:scoped:e");
        assertThat(all).startsWith("10:20:30:permit-ALL:e");
        assertThat(self).startsWith("10:20:30:permit-SELF:e");
        assertThat(all).isNotEqualTo(self);
    }
}
