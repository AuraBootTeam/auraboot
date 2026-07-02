package com.auraboot.framework.meta.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the field-format regex ReDoS guard
 * ({@link ValidationServiceImpl#matchesWithTimeout(Pattern, String)}).
 *
 * <p>Security regression: {@code validateFormat} compiled an admin-configured regex and
 * matched user-submitted values with no time bound → a catastrophic-backtracking pattern
 * plus crafted input hung the request thread (ReDoS). Matching is now time-bounded.
 */
@DisplayName("ValidationServiceImpl ReDoS guard")
class ValidationServiceRedosTest {

    @Test
    @DisplayName("normal patterns still match correctly")
    void normalPatternsMatch() {
        assertTrue(ValidationServiceImpl.matchesWithTimeout(Pattern.compile("[a-z]+"), "abc"));
        assertFalse(ValidationServiceImpl.matchesWithTimeout(Pattern.compile("[a-z]+"), "abc123"));
        assertTrue(ValidationServiceImpl.matchesWithTimeout(
                Pattern.compile("\\d{3}-\\d{4}"), "123-4567"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("a catastrophic-backtracking pattern is aborted (bounded), not hung")
    void catastrophicPatternIsBounded() {
        // (.*a){25}$ against a long non-matching input is a genuinely catastrophic pattern
        // in the HotSpot regex engine (the overlapping '.' backtracking is not optimized
        // away — empirically >3s unbounded). Without the guard this hangs; the 5s test
        // timeout would fail. The guard must abort it within REGEX_MATCH_TIMEOUT_MS.
        Pattern evil = Pattern.compile("(.*a){25}$");
        String input = "a".repeat(50) + "!";
        assertThrows(ValidationServiceImpl.RegexTimeoutException.class,
                () -> ValidationServiceImpl.matchesWithTimeout(evil, input));
    }
}
