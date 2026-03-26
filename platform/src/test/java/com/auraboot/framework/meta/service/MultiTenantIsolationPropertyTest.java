package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多租户隔离完整性属性测试
 * 
 * Feature: git-first-architecture-alignment
 * Property 8: 多租户隔离完整性
 * 
 * 对于任何多租户环境下的数据操作，唯一性约束和索引应该包含tenant_id，
 * 数据验证应该限制在租户范围内，防止跨租户约束冲突
 * 
 * 验证需求：7.1, 7.2, 7.3, 7.4
 * 
 * @author AuraBoot Team
 * @since 2.1.2
 */
@Slf4j
@DisplayName("Property 8: Multi-tenant Isolation Completeness")
public class MultiTenantIsolationPropertyTest {

    private final MultiTenantIndexManager multiTenantIndexManager = new MultiTenantIndexManager();
    private final Random random = new Random();
    
    /**
     * Property 8.1: 唯一索引必须包含tenant_id
     * 
     * 对于任何唯一索引DDL，如果是CREATE UNIQUE INDEX语句，
     * 则必须在列列表中包含tenant_id字段
     */
    @RepeatedTest(100)
    @DisplayName("Property 8.1: Unique indexes must include tenant_id")
    void property_8_1_uniqueIndexesMustIncludeTenantId() {
        // Arrange: 生成随机模型定义
        ModelDefinition model = generateRandomModelWithUniqueFields();
        
        // Act: 生成多租户索引DDL
        List<String> indexDDLs = multiTenantIndexManager.generateMultiTenantIndexDDLs(model);
        
        // Assert: 验证所有唯一索引包含tenant_id
        for (String ddl : indexDDLs) {
            if (ddl.toUpperCase().contains("CREATE UNIQUE INDEX")) {
                assertTrue(
                    ddl.toUpperCase().contains("TENANT_ID"),
                    "Unique index must include tenant_id: " + ddl
                );
                
                // 验证tenant_id在列列表中
                int openParen = ddl.indexOf('(');
                int closeParen = ddl.lastIndexOf(')');
                String columnList = ddl.substring(openParen + 1, closeParen);
                
                assertTrue(
                    columnList.toUpperCase().contains("TENANT_ID"),
                    "tenant_id must be in column list: " + columnList
                );
                
                log.debug("✓ Unique index validated: {}", ddl);
            }
        }
        
        log.info("Property 8.1 verified: {} unique indexes all include tenant_id", 
                indexDDLs.stream().filter(ddl -> ddl.contains("unique")).count());
    }
    
    /**
     * Property 8.2: 索引验证器正确识别违规
     * 
     * 对于任何唯一索引DDL，如果缺少tenant_id，
     * validateMultiTenantIndex应该返回false
     */
    @RepeatedTest(100)
    @DisplayName("Property 8.2: Index validator correctly identifies violations")
    void property_8_2_indexValidatorIdentifiesViolations() {
        // Arrange: 生成违规的唯一索引DDL（不包含tenant_id）
        String tableName = "tb_test_" + com.auraboot.framework.common.util.UniqueIdGenerator.generate().substring(0, 8);
        String columnName = "code_" + random.nextInt(1000);
        String violatingDDL = String.format(
            "CREATE UNIQUE INDEX idx_%s_%s ON %s (%s)",
            tableName, columnName, tableName, columnName
        );
        
        // Act: 验证违规DDL
        boolean isValid = multiTenantIndexManager.validateMultiTenantIndex(violatingDDL);
        
        // Assert: 应该识别为违规
        assertFalse(isValid, "Validator should reject unique index without tenant_id");
        
        // Arrange: 生成合规的唯一索引DDL（包含tenant_id）
        String compliantDDL = String.format(
            "CREATE UNIQUE INDEX idx_%s_%s_tenant ON %s (tenant_id, %s)",
            tableName, columnName, tableName, columnName
        );
        
        // Act: 验证合规DDL
        boolean isValidCompliant = multiTenantIndexManager.validateMultiTenantIndex(compliantDDL);
        
        // Assert: 应该识别为合规
        assertTrue(isValidCompliant, "Validator should accept unique index with tenant_id");
        
        log.debug("✓ Validator correctly identified violation and compliance");
    }
    
    /**
     * Property 8.3: 索引修复器正确添加tenant_id
     * 
     * 对于任何缺少tenant_id的唯一索引DDL，
     * fixMultiTenantIndex应该在列列表前添加tenant_id
     */
    @RepeatedTest(100)
    @DisplayName("Property 8.3: Index fixer correctly adds tenant_id")
    void property_8_3_indexFixerAddsTenantId() {
        // Arrange: 生成违规的唯一索引DDL
        String tableName = "tb_test_" + com.auraboot.framework.common.util.UniqueIdGenerator.generate().substring(0, 8);
        String columnName = "code_" + random.nextInt(1000);
        String violatingDDL = String.format(
            "CREATE UNIQUE INDEX idx_%s_%s ON %s (%s)",
            tableName, columnName, tableName, columnName
        );
        
        // Act: 修复违规DDL
        String fixedDDL = multiTenantIndexManager.fixMultiTenantIndex(violatingDDL);
        
        // Assert: 修复后的DDL应该包含tenant_id
        assertTrue(
            fixedDDL.toUpperCase().contains("TENANT_ID"),
            "Fixed DDL must include tenant_id"
        );
        
        // 验证tenant_id在列列表的第一位
        int openParen = fixedDDL.indexOf('(');
        int closeParen = fixedDDL.lastIndexOf(')');
        String columnList = fixedDDL.substring(openParen + 1, closeParen);
        String[] columns = columnList.split(",");
        
        assertTrue(
            columns[0].trim().equalsIgnoreCase("tenant_id"),
            "tenant_id should be the first column in fixed DDL"
        );
        
        // 验证修复后的DDL通过验证
        assertTrue(
            multiTenantIndexManager.validateMultiTenantIndex(fixedDDL),
            "Fixed DDL should pass validation"
        );
        
        log.debug("✓ Fixed DDL: {} -> {}", violatingDDL, fixedDDL);
    }
    
    /**
     * Property 8.4: 唯一约束生成器包含tenant_id
     * 
     * 对于任何唯一约束生成请求，生成的DDL应该在列列表中包含tenant_id，
     * 且tenant_id应该在第一位
     */
    @RepeatedTest(100)
    @DisplayName("Property 8.4: Unique constraint generator includes tenant_id")
    void property_8_4_uniqueConstraintGeneratorIncludesTenantId() {
        // Arrange: 生成随机表名和列名
        String tableName = "tb_test_" + com.auraboot.framework.common.util.UniqueIdGenerator.generate().substring(0, 8);
        String constraintName = "uk_" + tableName + "_code";
        List<String> columns = new ArrayList<>();
        columns.add("code");
        columns.add("type");
        
        // Act: 生成唯一约束DDL
        String constraintDDL = multiTenantIndexManager.generateMultiTenantUniqueConstraint(
            tableName, constraintName, columns
        );
        
        // Assert: 验证DDL包含tenant_id
        assertTrue(
            constraintDDL.toUpperCase().contains("TENANT_ID"),
            "Constraint DDL must include tenant_id"
        );
        
        // 验证tenant_id在列列表的第一位
        int openParen = constraintDDL.indexOf('(');
        int closeParen = constraintDDL.lastIndexOf(')');
        String columnList = constraintDDL.substring(openParen + 1, closeParen);
        String[] constraintColumns = columnList.split(",");
        
        assertTrue(
            constraintColumns[0].trim().equalsIgnoreCase("tenant_id"),
            "tenant_id should be the first column in constraint"
        );
        
        // 验证原始列也在约束中
        for (String column : columns) {
            assertTrue(
                columnList.toUpperCase().contains(column.toUpperCase()),
                "Original column " + column + " should be in constraint"
            );
        }
        
        log.debug("✓ Generated constraint: {}", constraintDDL);
    }
    
    /**
     * Property 8.5: 表约束验证器识别所有违规
     * 
     * 对于任何表的唯一约束列表，如果存在不包含tenant_id的约束，
     * validateTableConstraints应该返回invalid结果并列出所有违规
     */
    @RepeatedTest(100)
    @DisplayName("Property 8.5: Table constraint validator identifies all violations")
    void property_8_5_tableConstraintValidatorIdentifiesAllViolations() {
        // Arrange: 生成包含违规和合规约束的列表
        String tableName = "tb_test_" + com.auraboot.framework.common.util.UniqueIdGenerator.generate().substring(0, 8);
        List<MultiTenantIndexManager.UniqueConstraintInfo> constraints = new ArrayList<>();
        
        // 添加违规约束（不包含tenant_id）
        int violationCount = random.nextInt(3) + 1;
        for (int i = 0; i < violationCount; i++) {
            List<String> columns = new ArrayList<>();
            columns.add("code_" + i);
            constraints.add(new MultiTenantIndexManager.UniqueConstraintInfo(
                "uk_violation_" + i, columns
            ));
        }
        
        // 添加合规约束（包含tenant_id）
        int compliantCount = random.nextInt(3) + 1;
        for (int i = 0; i < compliantCount; i++) {
            List<String> columns = new ArrayList<>();
            columns.add("tenant_id");
            columns.add("code_" + i);
            constraints.add(new MultiTenantIndexManager.UniqueConstraintInfo(
                "uk_compliant_" + i, columns
            ));
        }
        
        // Act: 验证表约束
        MultiTenantIndexManager.MultiTenantIndexValidationResult result = 
            multiTenantIndexManager.validateTableConstraints(tableName, constraints);
        
        // Assert: 应该识别为invalid
        assertFalse(result.isValid(), "Table with violating constraints should be invalid");
        
        // 验证违规数量正确
        assertEquals(
            violationCount,
            result.getViolationCount(),
            "Should identify all violations"
        );
        
        // 验证违规列表不为空
        assertNotNull(result.getViolations(), "Violations list should not be null");
        assertEquals(
            violationCount,
            result.getViolations().size(),
            "Violations list size should match count"
        );
        
        log.info("✓ Validator identified {} violations out of {} total constraints",
                violationCount, constraints.size());
    }
    
    /**
     * 生成包含唯一字段的随机模型定义
     */
    private ModelDefinition generateRandomModelWithUniqueFields() {
        String modelCode = "test_model_" + com.auraboot.framework.common.util.UniqueIdGenerator.generate().substring(0, 8);
        
        List<FieldDefinition> fields = new ArrayList<>();
        
        // 添加主键字段
        FieldDefinition pkField = FieldDefinition.builder()
            .code("id")
            .columnName("id")
            .dataType("uuid")
            .primaryKey(true)
            .required(true)
            .build();
        fields.add(pkField);
        
        // 添加1-3个唯一字段
        int uniqueFieldCount = random.nextInt(3) + 1;
        for (int i = 0; i < uniqueFieldCount; i++) {
            FieldDefinition field = FieldDefinition.builder()
                .code("unique_field_" + i)
                .columnName("unique_field_" + i)
                .dataType("string")
                .unique(true)
                .required(false)
                .build();
            fields.add(field);
        }
        
        // 添加一些普通字段
        int normalFieldCount = random.nextInt(3) + 1;
        for (int i = 0; i < normalFieldCount; i++) {
            FieldDefinition field = FieldDefinition.builder()
                .code("field_" + i)
                .columnName("field_" + i)
                .dataType("string")
                .unique(false)
                .required(false)
                .build();
            fields.add(field);
        }
        
        return ModelDefinition.builder()
            .code(modelCode)
            .tableName("tb_" + modelCode)
            .fields(fields)
            .build();
    }
}
