package com.auraboot.framework.meta.util;

/**
 * 页面Key转换工具
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
public class PageKeyConverter {
    
    /**
     * 将页面Key转换为模型编码
     * @param pageKey 页面Key
     * @return 模型编码
     */
    public static String toModelCode(String pageKey) {
        if (pageKey == null || pageKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Page key cannot be null or empty");
        }
        
        String code = pageKey.replace("-", "_").toLowerCase();
        // Strip standard page-type suffixes to get the model code
        for (String suffix : new String[]{"_list", "_form", "_detail", "_dashboard"}) {
            if (code.endsWith(suffix)) {
                code = code.substring(0, code.length() - suffix.length());
                break;
            }
        }
        return code;
    }
    
    /**
     * 将模型编码转换为页面Key
     * @param modelCode 模型编码
     * @return 页面Key
     */
    public static String toPageKey(String modelCode) {
        if (modelCode == null || modelCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Model code cannot be null or empty");
        }
        
        // 简单的转换逻辑，可以根据实际需求调整
        return modelCode.replace("_", "-").toLowerCase();
    }
}