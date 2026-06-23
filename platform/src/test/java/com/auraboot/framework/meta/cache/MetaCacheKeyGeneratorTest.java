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

        assertThat(first).isEqualTo("10:20:30:scoped");
        assertThat(second).isEqualTo("10:21:31:scoped");
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void dataAccessContextSuffixSeparatesBypassState() {
        MetaContext.setContext(10L, 20L, "user-a", "user-a");
        MetaContext.setMemberId(30L);
        String scoped = MetaCacheKeyGenerator.getDataAccessContextSuffix();

        String bypassed = MetaContext.runWithoutDataPermission(
                MetaCacheKeyGenerator::getDataAccessContextSuffix);

        assertThat(scoped).isEqualTo("10:20:30:scoped");
        assertThat(bypassed).isEqualTo("10:20:30:bypass");
    }
}
