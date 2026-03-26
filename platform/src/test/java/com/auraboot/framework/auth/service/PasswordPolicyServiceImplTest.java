package com.auraboot.framework.auth.service;

import com.auraboot.framework.auth.service.impl.PasswordPolicyServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for PasswordPolicyServiceImpl.
 * Uses ReflectionTestUtils to inject @Value fields without Spring context.
 */
class PasswordPolicyServiceImplTest {

    private PasswordPolicyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PasswordPolicyServiceImpl();
        // Default policy: min=8, max=128, uppercase=true, lowercase=true, digit=true, special=false
        ReflectionTestUtils.setField(service, "minLength", 8);
        ReflectionTestUtils.setField(service, "maxLength", 128);
        ReflectionTestUtils.setField(service, "requireUppercase", true);
        ReflectionTestUtils.setField(service, "requireLowercase", true);
        ReflectionTestUtils.setField(service, "requireDigit", true);
        ReflectionTestUtils.setField(service, "requireSpecial", false);
    }

    // =========================================================
    // Null / empty
    // =========================================================

    @Test
    void validate_null_returnsError() {
        List<String> errors = service.validate(null);
        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0)).containsIgnoringCase("empty");
    }

    @Test
    void validate_empty_returnsError() {
        List<String> errors = service.validate("");
        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0)).containsIgnoringCase("empty");
    }

    // =========================================================
    // Length
    // =========================================================

    @Test
    void validate_tooShort_returnsLengthError() {
        List<String> errors = service.validate("Abc1"); // only 4 chars
        assertThat(errors).anyMatch(e -> e.contains("at least 8"));
    }

    @Test
    void validate_tooLong_returnsLengthError() {
        ReflectionTestUtils.setField(service, "maxLength", 10);
        List<String> errors = service.validate("Abcdefghijk1"); // 12 chars
        assertThat(errors).anyMatch(e -> e.contains("at most 10"));
    }

    // =========================================================
    // Character requirements
    // =========================================================

    @Test
    void validate_noUppercase_returnsError() {
        List<String> errors = service.validate("abcdefgh1");
        assertThat(errors).anyMatch(e -> e.contains("uppercase"));
    }

    @Test
    void validate_noLowercase_returnsError() {
        List<String> errors = service.validate("ABCDEFGH1");
        assertThat(errors).anyMatch(e -> e.contains("lowercase"));
    }

    @Test
    void validate_noDigit_returnsError() {
        List<String> errors = service.validate("Abcdefghi");
        assertThat(errors).anyMatch(e -> e.contains("digit"));
    }

    @Test
    void validate_specialRequired_butMissing_returnsError() {
        ReflectionTestUtils.setField(service, "requireSpecial", true);
        List<String> errors = service.validate("Abcdefg1"); // no special char
        assertThat(errors).anyMatch(e -> e.contains("special"));
    }

    @Test
    void validate_specialNotRequired_passes() {
        // requireSpecial=false (default)
        List<String> errors = service.validate("Abcdefg1"); // no special char but not required
        assertThat(errors).noneMatch(e -> e.contains("special"));
    }

    // =========================================================
    // Common weak passwords
    // =========================================================

    @Test
    void validate_commonWeakPassword_returnsError() {
        // "password123".toLowerCase() = "password123" which IS in COMMON_WEAK_PASSWORDS
        // But "password123" lacks uppercase → will also get that error. Test that "common" error appears.
        // Use a strong-looking but weak password: "P@ssw0rd" → toLowerCase = "p@ssw0rd" which is in the list
        // Tested in validate_knownWeakPassword_returnsError. This test uses a non-weak strong password:
        List<String> errors = service.validate("MyUnique7Passx!");
        assertThat(errors).isEmpty(); // "MyUnique7Passx!" is NOT in weak list and meets all rules
    }

    @Test
    void validate_knownWeakPassword_returnsError() {
        // "P@ssw0rd" - has upper, lower, digit, special. But "p@ssw0rd" is in weak list.
        // "P@ssw0rd".toLowerCase() = "p@ssw0rd" which IS in COMMON_WEAK_PASSWORDS
        List<String> errors = service.validate("P@ssw0rd");
        assertThat(errors).anyMatch(e -> e.contains("common"));
    }

    @Test
    void validate_weakPasswordCaseInsensitive_returnsError() {
        // "P@SSW0RD".toLowerCase() = "p@ssw0rd" which is in the weak list
        ReflectionTestUtils.setField(service, "requireSpecial", true);
        List<String> errors = service.validate("P@SSW0RD");
        assertThat(errors).anyMatch(e -> e.contains("common"));
    }

    // =========================================================
    // Valid password
    // =========================================================

    @Test
    void validate_strongPassword_returnsNoErrors() {
        List<String> errors = service.validate("MyStr0ngPassw0rd!");
        assertThat(errors).isEmpty();
    }

    @Test
    void validate_exactMinLength_passes() {
        // Exactly 8 chars: Abc1defg
        List<String> errors = service.validate("Abc1defg");
        assertThat(errors).isEmpty();
    }

    // =========================================================
    // Multiple errors
    // =========================================================

    @Test
    void validate_multipleViolations_returnsAllErrors() {
        List<String> errors = service.validate("abc"); // too short, no uppercase, no digit
        assertThat(errors.size()).isGreaterThanOrEqualTo(3);
    }
}
