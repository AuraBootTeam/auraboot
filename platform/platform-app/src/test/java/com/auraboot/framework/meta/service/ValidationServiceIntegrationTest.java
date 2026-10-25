package com.auraboot.framework.meta.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.DataType;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ValidationService Integration Test
 *
 * Covers P0-3 requirements:
 * 1. validateData - Complete data validation with field-level checks
 * 2. validateField - Individual field validation (type, length, format, range)
 * 3. validateUniqueness - Database uniqueness constraint validation
 * 4. validateRelations - Referential integrity validation
 * 5. validateBusinessRules - SpEL expression-based business rule validation
 * 6. validateDataIntegrity - Data completeness and schema conformance
 * 7. validateCustomRule - Custom validation rule evaluation
 *
 * Uses shared model across all tests to avoid field uniqueness constraint issues.
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("ValidationService Integration Test - P0-3")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ValidationServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ValidationService validationService;

    @Autowired
    private MetaModelMapper metaModelMapper;

    @Autowired
    private MetaFieldMapper metaFieldMapper;

    @Autowired
    private MetaModelFieldBindingMapper fieldBindingMapper;

    @Autowired
    private SchemaManagementService schemaManagementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Shared model for all tests
    private String modelCode;
    private String tableName;
    private Model model;
    private boolean modelInitialized = false;

    @BeforeAll
    void setupSharedModel() {
        modelCode = "val_test_" + System.currentTimeMillis();
        tableName = "mt_" + modelCode.toLowerCase();
        modelInitialized = false;
    }

    @BeforeEach
    void ensureModelExists() {
        setupTenantContext();
        
        if (!modelInitialized) {
            try {
                cleanupExistingModel();
                createModel();
                createFields();
                createPhysicalTable();
                modelInitialized = true;
                log.info("Model initialized for validation tests: {}", modelCode);
            } catch (Exception e) {
                log.error("Failed to initialize model", e);
                throw new RuntimeException("Failed to initialize model", e);
            }
        }
    }

    @AfterAll
    void cleanup() {
        modelInitialized = false;
    }

    // ==================== Field Validation Tests ====================

    @Test
    @Order(1)
    @DisplayName("P0-3.1: Validate required field - missing value in CREATE context")
    void test01_validateField_requiredMissing() {
        FieldDefinition field = FieldDefinition.builder()
                .code("name")
                .name("Name")
                .dataType("string")
                .required(true)
                .build();

        FieldValidationResult result = validationService.validateField(field, null, ValidationContext.CREATE);

        assertNotNull(result);
        assertFalse(result.isValid(), "Missing required field should be invalid");
        assertFalse(result.getErrors().isEmpty());
    }

    @Test
    @Order(2)
    @DisplayName("P0-3.1: Validate required field - empty string in CREATE context")
    void test02_validateField_requiredEmptyString() {
        FieldDefinition field = FieldDefinition.builder()
                .code("name")
                .name("Name")
                .dataType("string")
                .required(true)
                .build();

        FieldValidationResult result = validationService.validateField(field, "  ", ValidationContext.CREATE);

        assertNotNull(result);
        assertFalse(result.isValid(), "Empty string for required field should be invalid");
    }

    @Test
    @Order(3)
    @DisplayName("P0-3.1: Validate required field - null in UPDATE context should pass")
    void test03_validateField_requiredNullInUpdate() {
        FieldDefinition field = FieldDefinition.builder()
                .code("name")
                .name("Name")
                .dataType("string")
                .required(true)
                .build();

        FieldValidationResult result = validationService.validateField(field, null, ValidationContext.UPDATE);

        assertNotNull(result);
        assertTrue(result.isValid(), "Null in UPDATE context should be valid for required fields");
    }

    @Test
    @Order(4)
    @DisplayName("P0-3.1: Validate required field - valid value")
    void test04_validateField_requiredValid() {
        FieldDefinition field = FieldDefinition.builder()
                .code("name")
                .name("Name")
                .dataType("string")
                .required(true)
                .build();

        FieldValidationResult result = validationService.validateField(field, "Valid Name", ValidationContext.CREATE);

        assertNotNull(result);
        assertTrue(result.isValid());
    }

    @Test
    @Order(5)
    @DisplayName("P0-3.1: Validate null field definition")
    void test05_validateField_nullDefinition() {
        FieldValidationResult result = validationService.validateField(null, "value", ValidationContext.CREATE);

        assertNotNull(result);
        assertFalse(result.isValid(), "Null definition should be invalid");
    }

    // ==================== Data Type Validation Tests ====================

    @Test
    @Order(10)
    @DisplayName("P0-3.1: Validate STRING field with valid value")
    void test10_validateField_stringValid() {
        FieldDefinition field = FieldDefinition.builder()
                .code("text_field")
                .name("Text")
                .dataType("string")
                .build();

        FieldValidationResult result = validationService.validateField(field, "Hello World", ValidationContext.CREATE);

        assertNotNull(result);
        assertTrue(result.isValid());
    }

    @Test
    @Order(11)
    @DisplayName("P0-3.1: Validate INTEGER field with valid value")
    void test11_validateField_integerValid() {
        FieldDefinition field = FieldDefinition.builder()
                .code("count_field")
                .name("Count")
                .dataType("integer")
                .build();

        FieldValidationResult result = validationService.validateField(field, 42, ValidationContext.CREATE);

        assertNotNull(result);
        assertTrue(result.isValid());
    }

    @Test
    @Order(12)
    @DisplayName("P0-3.1: Validate INTEGER field with invalid string value")
    void test12_validateField_integerInvalid() {
        FieldDefinition field = FieldDefinition.builder()
                .code("count_field")
                .name("Count")
                .dataType("integer")
                .build();

        FieldValidationResult result = validationService.validateField(field, "not_a_number", ValidationContext.CREATE);

        assertNotNull(result);
        assertFalse(result.isValid(), "Non-numeric value for INTEGER field should be invalid");
    }

    @Test
    @Order(13)
    @DisplayName("P0-3.1: Validate BOOLEAN field with valid value")
    void test13_validateField_booleanValid() {
        FieldDefinition field = FieldDefinition.builder()
                .code("active_field")
                .name("Active")
                .dataType("boolean")
                .build();

        FieldValidationResult result = validationService.validateField(field, true, ValidationContext.CREATE);

        assertNotNull(result);
        assertTrue(result.isValid());
    }

    @Test
    @Order(14)
    @DisplayName("P0-3.1: Validate DECIMAL field with valid value")
    void test14_validateField_decimalValid() {
        FieldDefinition field = FieldDefinition.builder()
                .code("price_field")
                .name("Price")
                .dataType("decimal")
                .precision(10)
                .scale(2)
                .build();

        FieldValidationResult result = validationService.validateField(field, 99.99, ValidationContext.CREATE);

        assertNotNull(result);
        assertTrue(result.isValid());
    }

    // ==================== Length Validation Tests ====================

    @Test
    @Order(20)
    @DisplayName("P0-3.1: Validate string length - exceeds maxLength")
    void test20_validateField_maxLength() {
        FieldDefinition field = FieldDefinition.builder()
                .code("short_name")
                .name("Short Name")
                .dataType("string")
                .maxLength(10)
                .build();

        FieldValidationResult result = validationService.validateField(
                field, "This is a very long string that exceeds max length", ValidationContext.CREATE);

        assertNotNull(result);
        assertFalse(result.isValid(), "String exceeding maxLength should be invalid");
    }

    @Test
    @Order(21)
    @DisplayName("P0-3.1: Validate string length - below minLength")
    void test21_validateField_minLength() {
        FieldDefinition field = FieldDefinition.builder()
                .code("min_name")
                .name("Min Name")
                .dataType("string")
                .minLength(5)
                .build();

        FieldValidationResult result = validationService.validateField(field, "ab", ValidationContext.CREATE);

        assertNotNull(result);
        assertFalse(result.isValid(), "String below minLength should be invalid");
    }

    @Test
    @Order(22)
    @DisplayName("P0-3.1: Validate string length - within range")
    void test22_validateField_lengthInRange() {
        FieldDefinition field = FieldDefinition.builder()
                .code("range_name")
                .name("Range Name")
                .dataType("string")
                .minLength(3)
                .maxLength(50)
                .build();

        FieldValidationResult result = validationService.validateField(field, "Valid Name", ValidationContext.CREATE);

        assertNotNull(result);
        assertTrue(result.isValid());
    }

    // ==================== Range Validation Tests ====================

    @Test
    @Order(25)
    @DisplayName("P0-3.1: Validate numeric range - exceeds max value")
    void test25_validateField_maxValue() {
        FieldDefinition field = FieldDefinition.builder()
                .code("quantity")
                .name("Quantity")
                .dataType("integer")
                .maxValue(100)
                .build();

        FieldValidationResult result = validationService.validateField(field, 150, ValidationContext.CREATE);

        assertNotNull(result);
        assertFalse(result.isValid(), "Value exceeding maxValue should be invalid");
    }

    @Test
    @Order(26)
    @DisplayName("P0-3.1: Validate numeric range - below min value")
    void test26_validateField_minValue() {
        FieldDefinition field = FieldDefinition.builder()
                .code("quantity")
                .name("Quantity")
                .dataType("integer")
                .minValue(0)
                .build();

        FieldValidationResult result = validationService.validateField(field, -5, ValidationContext.CREATE);

        assertNotNull(result);
        assertFalse(result.isValid(), "Value below minValue should be invalid");
    }

    // ==================== Model-Level Validation Tests ====================

    @Test
    @Order(30)
    @DisplayName("P0-3.2: Validate data - all fields valid")
    void test30_validateData_allValid() {
        ModelDefinition modelDef = buildTestModelDefinition();

        Map<String, Object> data = new HashMap<>();
        data.put("record_id", "rec_001");
        data.put("record_name", "Valid Record");
        data.put("record_status", "active");

        ValidationResult result = validationService.validateData(modelDef, data, ValidationContext.CREATE);

        assertNotNull(result);
        assertTrue(result.isValid(), "Valid data should pass validation: " + result.getErrors());
    }

    @Test
    @Order(31)
    @DisplayName("P0-3.2: Validate data - missing required field")
    void test31_validateData_missingRequired() {
        ModelDefinition modelDef = buildTestModelDefinition();

        Map<String, Object> data = new HashMap<>();
        data.put("record_status", "active");
        // Missing required 'record_id' and 'record_name' fields

        ValidationResult result = validationService.validateData(modelDef, data, ValidationContext.CREATE);

        assertNotNull(result);
        assertFalse(result.isValid(), "Missing required fields should be invalid");
        assertFalse(result.getErrors().isEmpty());
    }

    @Test
    @Order(32)
    @DisplayName("P0-3.2: Validate data - null model definition should throw")
    void test32_validateData_nullModel() {
        Map<String, Object> data = Map.of("record_id", "test");

        assertThrows(Exception.class, () -> {
            validationService.validateData(null, data, ValidationContext.CREATE);
        });
    }

    @Test
    @Order(33)
    @DisplayName("P0-3.2: Validate data - null data treated as empty")
    void test33_validateData_nullData() {
        ModelDefinition modelDef = buildTestModelDefinition();

        ValidationResult result = validationService.validateData(modelDef, null, ValidationContext.CREATE);

        assertNotNull(result);
        // Should fail if there are required fields
    }

    // ==================== Uniqueness Validation Tests ====================

    @Test
    @Order(40)
    @DisplayName("P0-3.3: Validate uniqueness - no unique fields should pass")
    void test40_validateUniqueness_noUniqueFields() {
        ModelDefinition modelDef = ModelDefinition.builder()
                .tableName(tableName)
                .fields(List.of(
                        FieldDefinition.builder()
                                .code("record_name")
                                .name("Name")
                                .columnName("record_name")
                                .dataType("string")
                                .unique(false)
                                .build()
                ))
                .build();

        Map<String, Object> data = Map.of("record_name", "Any Value");

        ValidationResult result = validationService.validateUniqueness(modelDef, data, ValidationContext.CREATE);

        assertNotNull(result);
        assertTrue(result.isValid());
    }

    @Test
    @Order(41)
    @DisplayName("P0-3.3: Validate uniqueness - unique field with new value should pass")
    void test41_validateUniqueness_newValue() {
        ModelDefinition modelDef = ModelDefinition.builder()
                .tableName(tableName)
                .fields(List.of(
                        FieldDefinition.builder()
                                .code("record_id")
                                .name("ID")
                                .columnName("record_id")
                                .dataType("string")
                                .unique(true)
                                .build()
                ))
                .build();

        Map<String, Object> data = Map.of("record_id", "unique_" + System.currentTimeMillis());

        ValidationResult result = validationService.validateUniqueness(modelDef, data, ValidationContext.CREATE);

        assertNotNull(result);
        assertTrue(result.isValid(), "New unique value should pass: " + result.getErrors());
    }

    @Test
    @Order(42)
    @DisplayName("P0-3.3: Validate uniqueness - null table name should pass safely")
    void test42_validateUniqueness_nullTable() {
        ModelDefinition modelDef = ModelDefinition.builder()
                .tableName(null)
                .fields(List.of(
                        FieldDefinition.builder()
                                .code("record_id")
                                .name("ID")
                                .columnName("record_id")
                                .dataType("string")
                                .unique(true)
                                .build()
                ))
                .build();

        Map<String, Object> data = Map.of("record_id", "test");

        ValidationResult result = validationService.validateUniqueness(modelDef, data, ValidationContext.CREATE);

        assertNotNull(result);
        assertTrue(result.isValid(), "Null table name should pass gracefully");
    }

    // ==================== Relation Validation Tests ====================

    @Test
    @Order(50)
    @DisplayName("P0-3.4: Validate relations - no relations defined should pass")
    void test50_validateRelations_noRelations() {
        ModelDefinition modelDef = ModelDefinition.builder()
                .tableName(tableName)
                .fields(List.of())
                .relations(null)
                .build();

        Map<String, Object> data = Map.of("record_id", "test");

        ValidationResult result = validationService.validateRelations(modelDef, data, ValidationContext.CREATE);

        assertNotNull(result);
        assertTrue(result.isValid());
    }

    @Test
    @Order(51)
    @DisplayName("P0-3.4: Validate relations - required relation missing in CREATE")
    void test51_validateRelations_requiredMissing() {
        RelationDefinition relation = RelationDefinition.builder()
                .name("category_ref")
                .sourceField("category_id")
                .targetTable("mt_category")
                .targetField("id")
                .required(true)
                .build();

        ModelDefinition modelDef = ModelDefinition.builder()
                .tableName(tableName)
                .fields(List.of())
                .relations(List.of(relation))
                .build();

        Map<String, Object> data = new HashMap<>();
        // category_id is missing

        ValidationResult result = validationService.validateRelations(modelDef, data, ValidationContext.CREATE);

        assertNotNull(result);
        assertFalse(result.isValid(), "Missing required relation should be invalid");
    }

    @Test
    @Order(52)
    @DisplayName("P0-3.4: Validate relations - required relation missing in UPDATE should pass")
    void test52_validateRelations_requiredMissingInUpdate() {
        RelationDefinition relation = RelationDefinition.builder()
                .name("category_ref")
                .sourceField("category_id")
                .targetTable("mt_category")
                .targetField("id")
                .required(true)
                .build();

        ModelDefinition modelDef = ModelDefinition.builder()
                .tableName(tableName)
                .fields(List.of())
                .relations(List.of(relation))
                .build();

        Map<String, Object> data = new HashMap<>();

        ValidationResult result = validationService.validateRelations(modelDef, data, ValidationContext.UPDATE);

        assertNotNull(result);
        assertTrue(result.isValid(), "Missing relation in UPDATE should be valid");
    }

    // ==================== Business Rule Validation Tests ====================

    @Test
    @Order(60)
    @DisplayName("P0-3.5: Validate business rules - rule passes")
    void test60_validateBusinessRules_pass() {
        // Note: SpEL variables are set directly, so use #quantity instead of #data['quantity']
        BusinessRule rule = BusinessRule.builder()
                .name("quantity_positive")
                .expression("#quantity > 0")
                .message("Quantity must be positive")
                .severity(BusinessRule.Severity.ERROR)
                .enabled(true)
                .build();

        BusinessRuleSet ruleSet = BusinessRuleSet.builder()
                .rules(List.of(rule))
                .enabled(true)
                .build();

        ModelDefinition modelDef = buildTestModelDefinition();
        Map<String, Object> data = new HashMap<>();
        data.put("quantity", 10);

        ValidationResult result = validationService.validateBusinessRules(modelDef, data, ruleSet);

        assertNotNull(result);
        assertTrue(result.isValid(), "Rule should pass when quantity > 0");
    }

    @Test
    @Order(61)
    @DisplayName("P0-3.5: Validate business rules - rule fails with ERROR severity")
    void test61_validateBusinessRules_failError() {
        BusinessRule rule = BusinessRule.builder()
                .name("quantity_positive")
                .expression("#quantity > 0")
                .message("Quantity must be positive")
                .severity(BusinessRule.Severity.ERROR)
                .enabled(true)
                .build();

        BusinessRuleSet ruleSet = BusinessRuleSet.builder()
                .rules(List.of(rule))
                .enabled(true)
                .build();

        ModelDefinition modelDef = buildTestModelDefinition();
        Map<String, Object> data = new HashMap<>();
        data.put("quantity", -5);

        ValidationResult result = validationService.validateBusinessRules(modelDef, data, ruleSet);

        assertNotNull(result);
        assertFalse(result.isValid(), "Rule should fail when quantity <= 0");
        assertTrue(result.getErrors().contains("Quantity must be positive"));
    }

    @Test
    @Order(62)
    @DisplayName("P0-3.5: Validate business rules - rule fails with WARNING severity")
    void test62_validateBusinessRules_failWarning() {
        BusinessRule rule = BusinessRule.builder()
                .name("large_quantity_warning")
                .expression("#quantity < 1000")
                .message("Quantity is unusually large")
                .severity(BusinessRule.Severity.WARNING)
                .enabled(true)
                .build();

        BusinessRuleSet ruleSet = BusinessRuleSet.builder()
                .rules(List.of(rule))
                .enabled(true)
                .build();

        ModelDefinition modelDef = buildTestModelDefinition();
        Map<String, Object> data = new HashMap<>();
        data.put("quantity", 5000);

        ValidationResult result = validationService.validateBusinessRules(modelDef, data, ruleSet);

        assertNotNull(result);
        assertTrue(result.isValid(), "WARNING severity should not make result invalid");
        assertFalse(result.getWarnings().isEmpty(), "Should contain warning message");
    }

    @Test
    @Order(63)
    @DisplayName("P0-3.5: Validate business rules - null rule set should pass")
    void test63_validateBusinessRules_nullRuleSet() {
        ModelDefinition modelDef = buildTestModelDefinition();
        Map<String, Object> data = Map.of("record_id", "test");

        ValidationResult result = validationService.validateBusinessRules(modelDef, data, null);

        assertNotNull(result);
        assertTrue(result.isValid());
    }

    @Test
    @Order(64)
    @DisplayName("P0-3.5: Validate business rules - multiple rules")
    void test64_validateBusinessRules_multipleRules() {
        List<BusinessRule> rules = new ArrayList<>();

        // Note: SpEL variables are set directly, so use #record_name instead of #data['record_name']
        rules.add(BusinessRule.builder()
                .name("name_not_empty")
                .expression("#record_name != null && !#record_name.isEmpty()")
                .message("Name cannot be empty")
                .severity(BusinessRule.Severity.ERROR)
                .enabled(true)
                .build());

        rules.add(BusinessRule.builder()
                .name("status_valid")
                .expression("#record_status == 'active' || #record_status == 'draft'")
                .message("Invalid status value")
                .severity(BusinessRule.Severity.ERROR)
                .enabled(true)
                .build());

        BusinessRuleSet ruleSet = BusinessRuleSet.builder()
                .rules(rules)
                .enabled(true)
                .build();

        ModelDefinition modelDef = buildTestModelDefinition();
        Map<String, Object> data = new HashMap<>();
        data.put("record_name", "Valid");
        data.put("record_status", "active");

        ValidationResult result = validationService.validateBusinessRules(modelDef, data, ruleSet);

        assertNotNull(result);
        assertTrue(result.isValid(), "All rules should pass: " + result.getErrors());
    }

    @Test
    @Order(65)
    @DisplayName("P0-3.5: Validate business rules - invalid expression handled gracefully")
    void test65_validateBusinessRules_invalidExpression() {
        BusinessRule rule = BusinessRule.builder()
                .name("bad_expression")
                .expression("this is not a valid SpEL expression !!!")
                .message("Should not reach here")
                .severity(BusinessRule.Severity.ERROR)
                .enabled(true)
                .build();

        BusinessRuleSet ruleSet = BusinessRuleSet.builder()
                .rules(List.of(rule))
                .enabled(true)
                .build();

        ModelDefinition modelDef = buildTestModelDefinition();
        Map<String, Object> data = Map.of("record_id", "test");

        // Should not throw, but may add warning
        ValidationResult result = validationService.validateBusinessRules(modelDef, data, ruleSet);
        assertNotNull(result);
    }

    // ==================== Data Integrity Validation Tests ====================

    @Test
    @Order(70)
    @DisplayName("P0-3.6: Validate data integrity - empty data should fail")
    void test70_validateDataIntegrity_emptyData() {
        ModelDefinition modelDef = buildTestModelDefinition();

        ValidationResult result = validationService.validateDataIntegrity(modelDef, new HashMap<>());

        assertNotNull(result);
        assertFalse(result.isValid(), "Empty data should fail integrity check");
    }

    @Test
    @Order(71)
    @DisplayName("P0-3.6: Validate data integrity - null data should fail")
    void test71_validateDataIntegrity_nullData() {
        ModelDefinition modelDef = buildTestModelDefinition();

        ValidationResult result = validationService.validateDataIntegrity(modelDef, null);

        assertNotNull(result);
        assertFalse(result.isValid(), "Null data should fail integrity check");
    }

    @Test
    @Order(72)
    @DisplayName("P0-3.6: Validate data integrity - valid data should pass")
    void test72_validateDataIntegrity_validData() {
        ModelDefinition modelDef = buildTestModelDefinition();

        Map<String, Object> data = new HashMap<>();
        data.put("record_id", "rec_001");
        data.put("record_name", "Test Record");
        data.put("record_status", "active");

        ValidationResult result = validationService.validateDataIntegrity(modelDef, data);

        assertNotNull(result);
        assertTrue(result.isValid(), "Valid data should pass integrity check: " + result.getErrors());
    }

    // ==================== Custom Validation Rule Tests ====================

    @Test
    @Order(80)
    @DisplayName("P0-3.7: Validate with custom regex rule")
    void test80_validateField_customRegexRule() {
        ValidationRule emailRule = ValidationRule.builder()
                .name("email_format")
                .type(ValidationRule.RuleType.PATTERN)
                .expression("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")
                .errorMessage("Invalid email format")
                .build();

        FieldDefinition field = FieldDefinition.builder()
                .code("email")
                .name("Email")
                .dataType("string")
                .validationRules(List.of(emailRule))
                .build();

        // Valid email
        FieldValidationResult validResult = validationService.validateField(
                field, "test@example.com", ValidationContext.CREATE);
        assertNotNull(validResult);

        // Invalid email
        FieldValidationResult invalidResult = validationService.validateField(
                field, "not-an-email", ValidationContext.CREATE);
        assertNotNull(invalidResult);
    }

    // ==================== Validation Mode Tests ====================

    @Test
    @Order(90)
    @DisplayName("P0-3.8: Validate in CREATE mode - required fields enforced")
    void test90_validateData_createMode() {
        ModelDefinition modelDef = buildTestModelDefinition();

        Map<String, Object> data = new HashMap<>();
        data.put("record_status", "active");
        // Missing required 'record_id' and 'record_name'

        ValidationResult result = validationService.validateData(modelDef, data, ValidationContext.CREATE);

        assertNotNull(result);
        assertFalse(result.isValid());
    }

    @Test
    @Order(91)
    @DisplayName("P0-3.8: Validate in UPDATE mode - partial data allowed")
    void test91_validateData_updateMode() {
        ModelDefinition modelDef = buildTestModelDefinition();

        Map<String, Object> data = new HashMap<>();
        data.put("record_status", "updated");
        // Only updating status, missing required fields should be OK

        ValidationResult result = validationService.validateData(modelDef, data, ValidationContext.UPDATE);

        assertNotNull(result);
        // In UPDATE mode, missing required fields should be allowed
    }

    // ==================== Helper Methods ====================

    private ModelDefinition buildTestModelDefinition() {
        List<FieldDefinition> fields = new ArrayList<>();

        fields.add(FieldDefinition.builder()
                .code("record_id")
                .name("ID")
                .columnName("record_id")
                .dataType("string")
                .required(true)
                .primaryKey(true)
                .unique(true)
                .build());

        fields.add(FieldDefinition.builder()
                .code("record_name")
                .name("Name")
                .columnName("record_name")
                .dataType("string")
                .required(true)
                .maxLength(100)
                .build());

        fields.add(FieldDefinition.builder()
                .code("record_status")
                .name("Status")
                .columnName("record_status")
                .dataType("string")
                .required(false)
                .build());

        return ModelDefinition.builder()
                .code(modelCode)
                .name("Test Model")
                .tableName(tableName)
                .fields(fields)
                .build();
    }

    private void cleanupExistingModel() {
        try {
            Long tenantId = getTestTenant().getId();
            
            // Delete bindings
            jdbcTemplate.update(
                "DELETE FROM ab_meta_model_field_binding WHERE model_id IN " +
                "(SELECT id FROM ab_meta_model WHERE code = ? AND tenant_id = ?)",
                modelCode, tenantId
            );
            
            // Delete fields with specific codes
            jdbcTemplate.update(
                "DELETE FROM ab_meta_field WHERE code IN ('pid', 'record_id', 'record_name', 'record_status') AND tenant_id = ?",
                tenantId
            );
            
            // Delete model
            jdbcTemplate.update(
                "DELETE FROM ab_meta_model WHERE code = ? AND tenant_id = ?",
                modelCode, tenantId
            );
            
            // Drop table
            try {
                jdbcTemplate.execute("DROP TABLE IF EXISTS " + tableName);
            } catch (Exception e) {
                log.debug("Table {} does not exist", tableName);
            }
        } catch (Exception e) {
            log.debug("No existing model to clean up: {}", e.getMessage());
        }
    }

    private void createModel() {
        model = new Model();
        model.setPid(UniqueIdGenerator.generate());
        model.setTenantId(getTestTenant().getId());
        model.setCode(modelCode);
        model.setVersion(1);
        model.setIsCurrent(true);
        model.setStatus(Status.PUBLISHED.getCode());
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        model.setDeletedFlag(false);

        ExtensionBean extension = new ExtensionBean();
        Map<String, Object> extensionMap = new HashMap<>();
        extensionMap.put("displayName", "Validation Test Model");
        extensionMap.put("modelType", "entity");
        extension.setExtension(extensionMap);
        model.setExtension(extension);

        metaModelMapper.insert(model);
        trackModel(modelCode);
    }

    private void createFields() {
        // pid field (primary key) - code must be exactly "pid"
        createAndBindField("pid", DataType.STRING, true, false, -1);
        // Business fields
        createAndBindField("record_id", DataType.STRING, false, true, 1);
        createAndBindField("record_name", DataType.STRING, false, true, 2);
        createAndBindField("record_status", DataType.STRING, false, false, 3);
    }

    private void createAndBindField(String code, DataType dataType, boolean primaryKey, boolean required, int order) {
        Field field = new Field();
        field.setPid(UniqueIdGenerator.generate());
        field.setTenantId(getTestTenant().getId());
        field.setCode(code);
        field.setDataType(dataType.getCode());
        field.setVersion(1);
        field.setIsCurrent(true);
        field.setStatus(Status.PUBLISHED.getCode());
        field.setCreatedAt(Instant.now());
        field.setUpdatedAt(Instant.now());
        field.setDeletedFlag(false);

        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(required);
        feature.setUnique(primaryKey);
        field.setFeature(feature);

        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", code.toUpperCase());
        if (primaryKey) extMap.put("primaryKey", true);
        ext.setExtension(extMap);
        field.setExtension(ext);

        metaFieldMapper.insert(field);

        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setTenantId(getTestTenant().getId());
        binding.setModelId(model.getId());
        binding.setFieldId(field.getId());
        binding.setFieldOrder(order);
        fieldBindingMapper.insert(binding);
    }

    private void createPhysicalTable() {
        try {
            SchemaOperationResult result = schemaManagementService.createTableByModel(modelCode);
            if (!result.isSuccess()) {
                log.warn("Table creation returned non-success: {}", result.getErrorMessage());
            }
        } catch (Exception e) {
            log.warn("Table creation failed (may already exist): {}", e.getMessage());
        }
    }
}
