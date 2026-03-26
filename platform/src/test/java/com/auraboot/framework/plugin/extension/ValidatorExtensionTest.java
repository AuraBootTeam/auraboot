package com.auraboot.framework.plugin.extension;

import com.auraboot.framework.plugin.extension.ValidatorExtension.ValidationContext;
import com.auraboot.framework.plugin.extension.ValidatorExtension.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ValidatorExtension} extension point.
 *
 * Validates custom validator behavior including format validation,
 * success/error result creation, and null/empty value handling.
 *
 * Test IDs: C4-14 through C4-16
 *
 * @author AuraBoot Team
 */
@DisplayName("ValidatorExtension Unit Tests")
class ValidatorExtensionTest {

    // ── Inner test implementations ────────────────────────────────────────

    /**
     * Validator that checks asset codes against the pattern "AST-YYYYMM-NNNN".
     * Example valid format: "AST-202501-0001"
     */
    static class AssetCodeValidator implements ValidatorExtension {

        private static final Pattern ASSET_CODE_PATTERN =
                Pattern.compile("^AST-\\d{6}-\\d{4}$");

        @Override
        public String getValidatorKey() {
            return "asset:code-format";
        }

        @Override
        public ValidationResult validate(ValidationContext context) {
            Object value = context.value();

            // Handle null or empty values gracefully
            if (value == null) {
                return ValidationResult.error(context.fieldCode(), "Asset code must not be null");
            }

            String code = value.toString().trim();
            if (code.isEmpty()) {
                return ValidationResult.error(context.fieldCode(), "Asset code must not be empty");
            }

            // Validate format
            if (!ASSET_CODE_PATTERN.matcher(code).matches()) {
                return ValidationResult.error(
                        context.fieldCode(),
                        "Asset code must match format AST-YYYYMM-NNNN, got: " + code
                );
            }

            return ValidationResult.success();
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C4-14: Valid asset code 'AST-202501-0001' passes validation")
    void validate_validAssetCode_shouldReturnSuccess() {
        // Arrange
        var validator = new AssetCodeValidator();
        var context = ValidationContext.builder()
                .tenantId(1001L)
                .pluginId("asset-plugin")
                .namespace("asset")
                .validatorKey("asset:code-format")
                .fieldCode("assetCode")
                .value("AST-202501-0001")
                .recordData(Map.of("assetName", "Office Laptop"))
                .validatorParams(Map.of())
                .settings(Map.of())
                .build();

        // Act
        ValidationResult result = validator.validate(context);

        // Assert
        assertTrue(result.valid(), "Valid asset code should pass validation");
        assertTrue(result.errors().isEmpty(), "No errors expected for valid code");

        // Also verify supports() and getValidatorKey()
        assertEquals("asset:code-format", validator.getValidatorKey());
        assertTrue(validator.supports("asset:code-format"));
        assertFalse(validator.supports("other:validator"));
    }

    @Test
    @DisplayName("C4-15: Invalid asset code 'invalid' fails validation with error")
    void validate_invalidAssetCode_shouldReturnError() {
        // Arrange
        var validator = new AssetCodeValidator();
        var context = ValidationContext.builder()
                .tenantId(1001L)
                .pluginId("asset-plugin")
                .namespace("asset")
                .validatorKey("asset:code-format")
                .fieldCode("assetCode")
                .value("invalid")
                .recordData(Map.of())
                .validatorParams(Map.of())
                .settings(Map.of())
                .build();

        // Act
        ValidationResult result = validator.validate(context);

        // Assert
        assertFalse(result.valid(), "Invalid asset code should fail validation");
        assertFalse(result.errors().isEmpty(), "Should contain at least one error");
        assertEquals(1, result.errors().size());

        var error = result.errors().get(0);
        assertEquals("assetCode", error.field());
        assertTrue(error.message().contains("AST-YYYYMM-NNNN"),
                "Error message should describe the expected format");
        assertTrue(error.message().contains("invalid"),
                "Error message should include the invalid value");

        // Also test other invalid formats
        var contextBadMonth = ValidationContext.builder()
                .tenantId(1001L)
                .pluginId("asset-plugin")
                .namespace("asset")
                .validatorKey("asset:code-format")
                .fieldCode("assetCode")
                .value("AST-2025-01")
                .recordData(Map.of())
                .validatorParams(Map.of())
                .settings(Map.of())
                .build();

        var resultBadMonth = validator.validate(contextBadMonth);
        assertFalse(resultBadMonth.valid(), "Incorrectly formatted code should fail");

        var contextNoPrefix = ValidationContext.builder()
                .tenantId(1001L)
                .pluginId("asset-plugin")
                .namespace("asset")
                .validatorKey("asset:code-format")
                .fieldCode("assetCode")
                .value("202501-0001")
                .recordData(Map.of())
                .validatorParams(Map.of())
                .settings(Map.of())
                .build();

        var resultNoPrefix = validator.validate(contextNoPrefix);
        assertFalse(resultNoPrefix.valid(), "Code without AST- prefix should fail");
    }

    @Test
    @DisplayName("C4-16: Null and empty values are handled gracefully")
    void validate_nullAndEmptyValues_shouldReturnGracefulErrors() {
        // Arrange
        var validator = new AssetCodeValidator();

        // ---- Test null value ----
        var nullContext = ValidationContext.builder()
                .tenantId(1001L)
                .pluginId("asset-plugin")
                .namespace("asset")
                .validatorKey("asset:code-format")
                .fieldCode("assetCode")
                .value(null)
                .recordData(Map.of())
                .validatorParams(Map.of())
                .settings(Map.of())
                .build();

        ValidationResult nullResult = validator.validate(nullContext);

        assertFalse(nullResult.valid(), "Null value should fail validation");
        assertEquals(1, nullResult.errors().size());
        assertEquals("assetCode", nullResult.errors().get(0).field());
        assertTrue(nullResult.errors().get(0).message().contains("null"),
                "Error should mention null");

        // ---- Test empty string value ----
        var emptyContext = ValidationContext.builder()
                .tenantId(1001L)
                .pluginId("asset-plugin")
                .namespace("asset")
                .validatorKey("asset:code-format")
                .fieldCode("assetCode")
                .value("")
                .recordData(Map.of())
                .validatorParams(Map.of())
                .settings(Map.of())
                .build();

        ValidationResult emptyResult = validator.validate(emptyContext);

        assertFalse(emptyResult.valid(), "Empty value should fail validation");
        assertEquals(1, emptyResult.errors().size());
        assertEquals("assetCode", emptyResult.errors().get(0).field());
        assertTrue(emptyResult.errors().get(0).message().contains("empty"),
                "Error should mention empty");

        // ---- Test whitespace-only value ----
        var whitespaceContext = ValidationContext.builder()
                .tenantId(1001L)
                .pluginId("asset-plugin")
                .namespace("asset")
                .validatorKey("asset:code-format")
                .fieldCode("assetCode")
                .value("   ")
                .recordData(Map.of())
                .validatorParams(Map.of())
                .settings(Map.of())
                .build();

        ValidationResult whitespaceResult = validator.validate(whitespaceContext);

        assertFalse(whitespaceResult.valid(), "Whitespace-only value should fail validation");
        assertEquals(1, whitespaceResult.errors().size());

        // ---- Verify ValidationResult static factories ----
        var success = ValidationResult.success();
        assertTrue(success.valid());
        assertTrue(success.errors().isEmpty());

        var singleError = ValidationResult.error("Field is required");
        assertFalse(singleError.valid());
        assertEquals(1, singleError.errors().size());
        assertNull(singleError.errors().get(0).field());
        assertEquals("Field is required", singleError.errors().get(0).message());

        var fieldError = ValidationResult.error("email", "Invalid email format");
        assertFalse(fieldError.valid());
        assertEquals(1, fieldError.errors().size());
        assertEquals("email", fieldError.errors().get(0).field());
        assertEquals("Invalid email format", fieldError.errors().get(0).message());
    }
}
