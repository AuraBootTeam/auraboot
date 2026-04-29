package com.auraboot.framework.plugin.dto.imports;

/**
 * Types of resources that can be imported by plugins.
 * Database values are lowercase (matching CHECK constraint).
 */
public enum ResourceType {
    MODEL("ab_meta_model", "模型", OwnershipType.SHARED),
    FIELD("ab_meta_field", "字段", OwnershipType.SHARED),
    MODEL_FIELD_BINDING("ab_meta_model_field_binding", "模型字段绑定", OwnershipType.SHARED),
    COMMAND("ab_command_definition", "命令", OwnershipType.SHARED),
    BINDING_RULE("ab_binding_rule", "绑定规则", OwnershipType.SHARED),
    PERMISSION("ab_permission", "权限", OwnershipType.SHARED),
    ROLE("ab_role", "角色", OwnershipType.SHARED),
    ROLE_PERMISSION("ab_role_permission", "角色权限", OwnershipType.SHARED),
    MENU("ab_menu", "菜单", OwnershipType.SHARED),
    PROCESS("ab_bpm_process_definition", "流程", OwnershipType.SHARED),
    PAGE("ab_page_schema", "页面", OwnershipType.SHARED),
    DICT("ab_dict", "字典", OwnershipType.SHARED),
    NAMED_QUERY("ab_named_query", "命名查询", OwnershipType.SHARED),
    AGENT_DEFINITION("ab_agent_definition", "Agent 定义", OwnershipType.SHARED),
    DICT_ITEM("ab_dict_item", "字典项", OwnershipType.USER_CLAIMED),
    SAVED_VIEW("ab_saved_view", "保存视图", OwnershipType.SHARED),
    I18N("ab_i18n_resource", "国际化资源", OwnershipType.SHARED);

    private final String tableName;
    private final String displayName;
    private final OwnershipType defaultOwnership;

    ResourceType(String tableName, String displayName, OwnershipType defaultOwnership) {
        this.tableName = tableName;
        this.displayName = displayName;
        this.defaultOwnership = defaultOwnership;
    }

    /** Lowercase code for database storage (matches CHECK constraint). */
    public String code() {
        return name().toLowerCase();
    }

    public String getTableName() { return tableName; }
    public String getDisplayName() { return displayName; }
    public OwnershipType getDefaultOwnership() { return defaultOwnership; }

    public int getImportOrder() {
        return switch (this) {
            case DICT -> 10;
            case DICT_ITEM -> 11;
            case FIELD -> 20;
            case MODEL -> 30;
            case MODEL_FIELD_BINDING -> 31;
            case PERMISSION -> 40;
            case ROLE -> 50;
            case ROLE_PERMISSION -> 51;
            case MENU -> 60;
            case COMMAND -> 70;
            case BINDING_RULE -> 71;
            case NAMED_QUERY -> 75;
            case AGENT_DEFINITION -> 76;
            case PAGE -> 80;
            case SAVED_VIEW -> 82;
            case PROCESS -> 90;
            case I18N -> 95;
        };
    }

    public static ResourceType fromCode(String code) {
        if (code == null) return null;
        for (ResourceType t : values()) {
            if (t.code().equalsIgnoreCase(code)) return t;
        }
        return valueOf(code.toUpperCase());
    }
}
