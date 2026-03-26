package com.auraboot.framework.meta.constant;

/**
     * 数据类型枚举
     */
    public enum DataType {
        STRING("string", "字符串"),
        TEXT("text", "文本"),
        INTEGER("integer", "整数"),
        DECIMAL("decimal", "小数"),
        BOOLEAN("boolean", "布尔值"),
        DATE("date", "日期"),
        DATETIME("datetime", "日期时间"),
        JSON("json", "JSON对象"),
        ENUM("enum", "枚举"),
        REFERENCE("reference", "引用"),
        COMPUTED("computed", "计算字段"),
        AI_TEXT("ai_text", "AI生成文本"),
        MONEY("money", "Multi-currency amount field");
        
        private final String code;
        private final String name;
        
        DataType(String code, String name) {
            this.code = code;
            this.name = name;
        }
        
        public String getCode() {
            return code;
        }
        
        public String getName() {
            return name;
        }
    }
    