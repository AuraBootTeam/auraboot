package com.auraboot.framework.common.util;

/**
 * 命名风格转换工具类
 * 提供驼峰命名法与下划线命名法的互相转换
 */
public class NamingStyleConvertUtil {

    /**
     * 将驼峰命名转换为下划线命名
     * 例如: camelCase -> camel_case
     *
     * @param camelCaseStr 驼峰命名的字符串
     * @return 下划线命名的字符串
     */
    public static String camelToSnake(String camelCaseStr) {
        if (camelCaseStr == null || camelCaseStr.isEmpty()) {
            return camelCaseStr;
        }
        
        StringBuilder result = new StringBuilder();
        // 第一个字符直接添加
        result.append(Character.toLowerCase(camelCaseStr.charAt(0)));
        
        // 从第二个字符开始遍历
        for (int i = 1; i < camelCaseStr.length(); i++) {
            char ch = camelCaseStr.charAt(i);
            // 如果是大写字母，先添加下划线，再将字符转为小写添加
            if (Character.isUpperCase(ch)) {
                result.append('_');
                result.append(Character.toLowerCase(ch));
            } else {
                result.append(ch);
            }
        }
        
        return result.toString();
    }

    /**
     * 将下划线命名转换为驼峰命名
     * 例如: snake_case -> snakeCase
     *
     * @param snakeCaseStr 下划线命名的字符串
     * @return 驼峰命名的字符串
     */
    public static String snakeToCamel(String snakeCaseStr) {
        if (snakeCaseStr == null || snakeCaseStr.isEmpty()) {
            return snakeCaseStr;
        }
        
        StringBuilder result = new StringBuilder();
        boolean nextUpperCase = false;
        
        for (int i = 0; i < snakeCaseStr.length(); i++) {
            char ch = snakeCaseStr.charAt(i);
            
            if (ch == '_') {
                nextUpperCase = true;
            } else {
                if (nextUpperCase) {
                    result.append(Character.toUpperCase(ch));
                    nextUpperCase = false;
                } else {
                    result.append(ch);
                }
            }
        }
        
        return result.toString();
    }
}