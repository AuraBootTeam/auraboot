package com.auraboot.framework.application.support;

import com.auraboot.framework.common.util.NamingStyleConvertUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class NamingStyleConverterTest {

    @Test
    public void testCamelToSnake() {
        // 基本测试
        assertEquals("camel_case", NamingStyleConvertUtil.camelToSnake("camelCase"));
        assertEquals("snake_case", NamingStyleConvertUtil.camelToSnake("snakeCase"));
        
        // 测试已经是下划线格式的情况
        assertEquals("already_snake", NamingStyleConvertUtil.camelToSnake("already_snake"));
        
        // 测试首字母大写的情况
        assertEquals("pascal_case", NamingStyleConvertUtil.camelToSnake("PascalCase"));
        
//        // 测试连续大写字母的情况
//        assertEquals("api_response", NamingStyleConvertUtil.camelToSnake("APIResponse"));
//
        // 测试包含数字的情况
        assertEquals("user123_name", NamingStyleConvertUtil.camelToSnake("user123Name"));
        
        // 测试空字符串和null
        assertEquals("", NamingStyleConvertUtil.camelToSnake(""));
        assertNull(NamingStyleConvertUtil.camelToSnake(null));
    }

    @Test
    public void testSnakeToCamel() {
        // 基本测试
        assertEquals("camelCase", NamingStyleConvertUtil.snakeToCamel("camel_case"));
        assertEquals("snakeCase", NamingStyleConvertUtil.snakeToCamel("snake_case"));
        
        // 测试已经是驼峰格式的情况
        assertEquals("alreadyCamel", NamingStyleConvertUtil.snakeToCamel("alreadyCamel"));
        
        // 测试连续下划线的情况
        assertEquals("multipleUnderscores", NamingStyleConvertUtil.snakeToCamel("multiple__underscores"));
        
        // 测试以下划线开头或结尾的情况
//        assertEquals("startWithUnderscore", NamingStyleConvertUtil.snakeToCamel("_start_with_underscore"));
//        assertEquals("endWithUnderscore", NamingStyleConvertUtil.snakeToCamel("end_with_underscore_"));
        
        // 测试包含数字的情况
        assertEquals("user123Name", NamingStyleConvertUtil.snakeToCamel("user123_name"));
        
        // 测试空字符串和null
        assertEquals("", NamingStyleConvertUtil.snakeToCamel(""));
        assertNull(NamingStyleConvertUtil.snakeToCamel(null));
    }
}