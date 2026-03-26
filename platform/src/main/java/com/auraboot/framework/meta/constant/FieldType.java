package com.auraboot.framework.meta.constant;

/**
     * 字段类型枚举
     */
    public enum FieldType {
        INPUT("input", "输入框"),
        NUMBER("number", "数字输入框"),
        SELECT("select", "下拉选择"),
        RADIO("radio", "单选框"),
        CHECKBOX("checkbox", "复选框"),
        DATE("date", "日期选择器"),
        DATETIME("datetime", "日期时间选择器"),
        TEXTAREA("textarea", "文本域"),
        RICH_TEXT("rich_text", "富文本编辑器"),
        SWITCH("switch", "开关"),
        UPLOAD("upload", "文件上传"),
        CUSTOM("custom", "自定义组件"),
        AI_INPUT("ai_input", "AI输入框"),

    ;

    private final String code;
        private final String name;
        
        FieldType(String code, String name) {
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