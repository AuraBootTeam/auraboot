package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.meta.dto.FieldDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 主键类型系统一致性属性测试
 * 
 * Feature: git-first-architecture-alignment
 * Property 6: 主键类型系统一致性
 * 
 * 属性：对于任何字段定义，TypeSystemManager应该能够根据字段类型生成正确类型的主键，
 * 支持UUID、Long、Integer等多种类型，生成的主键值应该唯一且符合类型约束
 * 
 * 验证：需求5.1, 5.2, 5.3, 5.4, 5.5
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@DisplayName("Property 6: Primary Key Type System Consistency")
public class PrimaryKeyTypeSystemPropertyTest {

    @Autowired
    private TypeSystemManager typeSystemManager;

    private static final int PROPERTY_TEST_ITERATIONS = 100;


    @Test
    @DisplayName("Property 6.2: Long类型主键生成唯一性")
    void testLongPrimaryKeyUniqueness() {
        System.out.println("\n=== Property 6.2: Long类型主键生成唯一性 ===");
        
        Set<Object> generatedKeys = new HashSet<>();
        int successCount = 0;
        
        for (int i = 0; i < PROPERTY_TEST_ITERATIONS; i++) {
            // 创建Long类型的主键字段
            FieldDefinition pkField = FieldDefinition.builder()
                    .code("id")
                    .name("ID")
                    .dataType("long")
                    .required(true)
                    .build();
            
            // 生成主键
            Object pk = typeSystemManager.generatePrimaryKey(pkField);
            
            // 验证：主键应该是Long类型
            assertNotNull(pk);
            assertTrue(pk instanceof Long, "LONG primary key should be Long type");
            
            // 验证：主键应该唯一
            assertFalse(generatedKeys.contains(pk), 
                       "Generated primary key should be unique");
            generatedKeys.add(pk);
            
            // 验证：Long值应该为正数
            Long pkLong = (Long) pk;
            assertTrue(pkLong > 0, "Long primary key should be positive");
            
            successCount++;
        }
        
        System.out.println("✓ 完成 " + successCount + " 次迭代");
        System.out.println("✓ 生成了 " + generatedKeys.size() + " 个唯一的Long主键");
        System.out.println("✓ Long类型主键生成唯一性");
    }

    @Test
    @DisplayName("Property 6.3: Integer类型主键生成唯一性")
    void testIntegerPrimaryKeyUniqueness() {
        System.out.println("\n=== Property 6.3: Integer类型主键生成唯一性 ===");
        
        Set<Object> generatedKeys = new HashSet<>();
        int successCount = 0;
        
        for (int i = 0; i < PROPERTY_TEST_ITERATIONS; i++) {
            // 创建Integer类型的主键字段
            FieldDefinition pkField = FieldDefinition.builder()
                    .code("id")
                    .name("ID")
                    .dataType("integer")
                    .required(true)
                    .build();
            
            // 生成主键
            Object pk = typeSystemManager.generatePrimaryKey(pkField);
            
            // 验证：主键应该是Integer类型
            assertNotNull(pk);
            assertTrue(pk instanceof Integer, "INTEGER primary key should be Integer type");
            
            // 验证：主键应该唯一
            assertFalse(generatedKeys.contains(pk), 
                       "Generated primary key should be unique");
            generatedKeys.add(pk);
            
            // 验证：Integer值应该为正数
            Integer pkInt = (Integer) pk;
            assertTrue(pkInt > 0, "Integer primary key should be positive");
            
            successCount++;
        }
        
        System.out.println("✓ 完成 " + successCount + " 次迭代");
        System.out.println("✓ 生成了 " + generatedKeys.size() + " 个唯一的Integer主键");
        System.out.println("✓ Integer类型主键生成唯一性");
    }

    @Test
    @DisplayName("Property 6.4: 不同数据类型别名生成正确类型")
    void testDataTypeAliases() {
        System.out.println("\n=== Property 6.4: 不同数据类型别名生成正确类型 ===");
        
        int successCount = 0;
        
        // 测试UUID类型别名
        String[] uuidAliases = {"uuid", "string", "varchar", "text"};
        for (String alias : uuidAliases) {
            FieldDefinition pkField = FieldDefinition.builder()
                    .code("id")
                    .dataType(alias)
                    .build();
            
            Object pk = typeSystemManager.generatePrimaryKey(pkField);
            assertTrue(pk instanceof String, 
                      alias + " should generate String type primary key");
            successCount++;
        }
        
        // 测试Long类型别名
        String[] longAliases = {"long", "bigint"};
        for (String alias : longAliases) {
            FieldDefinition pkField = FieldDefinition.builder()
                    .code("id")
                    .dataType(alias)
                    .build();
            
            Object pk = typeSystemManager.generatePrimaryKey(pkField);
            assertTrue(pk instanceof Long, 
                      alias + " should generate Long type primary key");
            successCount++;
        }
        
        // 测试Integer类型别名
        String[] intAliases = {"integer", "int"};
        for (String alias : intAliases) {
            FieldDefinition pkField = FieldDefinition.builder()
                    .code("id")
                    .dataType(alias)
                    .build();
            
            Object pk = typeSystemManager.generatePrimaryKey(pkField);
            assertTrue(pk instanceof Integer, 
                      alias + " should generate Integer type primary key");
            successCount++;
        }
        
        System.out.println("✓ 完成 " + successCount + " 次迭代");
        System.out.println("✓ 不同数据类型别名生成正确类型");
    }


    @Test
    @DisplayName("Property 6.6: 数据类型转换正确性")
    void testDataTypeConversion() {
        System.out.println("\n=== Property 6.6: 数据类型转换正确性 ===");
        
        int successCount = 0;
        
        for (int i = 0; i < PROPERTY_TEST_ITERATIONS; i++) {
            // 测试String转换
            FieldDefinition stringField = FieldDefinition.builder()
                    .code("name")
                    .dataType("string")
                    .build();
            Object strResult = typeSystemManager.convertValue(123, stringField);
            assertTrue(strResult instanceof String);
            assertEquals("123", strResult);
            
            // 测试Long转换
            FieldDefinition longField = FieldDefinition.builder()
                    .code("count")
                    .dataType("long")
                    .build();
            Object longResult = typeSystemManager.convertValue("456", longField);
            assertTrue(longResult instanceof Long);
            assertEquals(456L, longResult);
            
            // 测试Integer转换
            FieldDefinition intField = FieldDefinition.builder()
                    .code("age")
                    .dataType("integer")
                    .build();
            Object intResult = typeSystemManager.convertValue("789", intField);
            assertTrue(intResult instanceof Integer);
            assertEquals(789, intResult);
            
            // 测试Boolean转换
            FieldDefinition boolField = FieldDefinition.builder()
                    .code("active")
                    .dataType("boolean")
                    .build();
            Object boolResult = typeSystemManager.convertValue("true", boolField);
            assertTrue(boolResult instanceof Boolean);
            assertEquals(true, boolResult);
            
            successCount++;
        }
        
        System.out.println("✓ 完成 " + successCount + " 次迭代");
        System.out.println("✓ 数据类型转换正确性");
    }

    @Test
    @DisplayName("Property 6.7: 类型验证正确性")
    void testTypeValidation() {
        System.out.println("\n=== Property 6.7: 类型验证正确性 ===");
        
        int successCount = 0;
        
        for (int i = 0; i < PROPERTY_TEST_ITERATIONS; i++) {
            // 测试有效类型
            FieldDefinition intField = FieldDefinition.builder()
                    .code("age")
                    .dataType("integer")
                    .build();
            
            assertTrue(typeSystemManager.isValidType(123, intField), 
                      "Integer value should be valid for INTEGER field");
            assertTrue(typeSystemManager.isValidType("456", intField), 
                      "String number should be valid for INTEGER field");
            assertFalse(typeSystemManager.isValidType("abc", intField), 
                       "Non-numeric string should be invalid for INTEGER field");
            
            // 测试null值总是有效
            assertTrue(typeSystemManager.isValidType(null, intField), 
                      "null value should always be valid");
            
            successCount++;
        }
        
        System.out.println("✓ 完成 " + successCount + " 次迭代");
        System.out.println("✓ 类型验证正确性");
    }

    @Test
    @DisplayName("Property 6.8: SQL类型和Java类型映射一致性")
    void testSqlJavaTypeMapping() {
        System.out.println("\n=== Property 6.8: SQL类型和Java类型映射一致性 ===");
        
        int successCount = 0;
        
        // 测试Java类型到SQL类型的映射
        assertEquals("VARCHAR(255)", typeSystemManager.getSqlType("string"));
        assertEquals("bigint", typeSystemManager.getSqlType("long"));
        assertEquals("integer", typeSystemManager.getSqlType("integer"));
        assertEquals("boolean", typeSystemManager.getSqlType("boolean"));
        assertEquals("DECIMAL(19,4)", typeSystemManager.getSqlType("decimal"));
        assertEquals("date", typeSystemManager.getSqlType("date"));
        assertEquals("timestamp", typeSystemManager.getSqlType("datetime"));
        
        // 测试SQL类型到Java类型的映射
        assertEquals("string", typeSystemManager.getJavaType("VARCHAR(255)"));
        assertEquals("long", typeSystemManager.getJavaType("bigint"));
        assertEquals("integer", typeSystemManager.getJavaType("integer"));
        assertEquals("boolean", typeSystemManager.getJavaType("boolean"));
        assertEquals("decimal", typeSystemManager.getJavaType("DECIMAL(19,4)"));
        assertEquals("date", typeSystemManager.getJavaType("date"));
        assertEquals("datetime", typeSystemManager.getJavaType("timestamp"));
        
        // 测试往返转换一致性
        String[] javaTypes = {"string", "long", "integer", "boolean", "decimal", "date", "datetime"};
        for (String javaType : javaTypes) {
            String sqlType = typeSystemManager.getSqlType(javaType);
            String backToJava = typeSystemManager.getJavaType(sqlType);
            assertEquals(javaType, backToJava, 
                        "Round-trip conversion should preserve Java type: " + javaType);
            successCount++;
        }
        
        System.out.println("✓ 完成 " + successCount + " 次迭代");
        System.out.println("✓ SQL类型和Java类型映射一致性");
    }
}
