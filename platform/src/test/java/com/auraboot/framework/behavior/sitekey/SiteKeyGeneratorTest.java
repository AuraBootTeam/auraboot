package com.auraboot.framework.behavior.sitekey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class SiteKeyGeneratorTest {

    private static final Pattern KEY_PATTERN = Pattern.compile("^abk_[0-9A-Za-z]{32}$");

    @Test
    @DisplayName("generates abk_ prefixed base62 key of fixed length")
    void generatesWellFormedKey() {
        String key = SiteKeyGenerator.generate();
        assertThat(key).startsWith("abk_");
        assertThat(key).hasSize(SiteKeyGenerator.PREFIX.length() + SiteKeyGenerator.KEY_LENGTH);
        assertThat(KEY_PATTERN.matcher(key).matches())
                .as("key %s must match abk_<32 base62>", key)
                .isTrue();
    }

    @Test
    @DisplayName("keys are unguessable — 10k generations produce no collisions")
    void keysAreUnique() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            keys.add(SiteKeyGenerator.generate());
        }
        assertThat(keys).hasSize(10_000);
    }

    @Test
    @DisplayName("body uses only base62 characters (no -, _, or padding)")
    void usesOnlyBase62() {
        String body = SiteKeyGenerator.generate().substring(SiteKeyGenerator.PREFIX.length());
        assertThat(body).matches("[0-9A-Za-z]+");
        assertThat(body).doesNotContain("-").doesNotContain("_").doesNotContain("=");
    }
}
