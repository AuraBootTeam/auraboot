package com.auraboot.framework.meta.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Meta 模块错误码枚举
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Getter
@AllArgsConstructor
public enum MetaErrorCode {
    
    // ==================== 通用错误 (1000-1099) ====================
    RESOURCE_NOT_FOUND(1000, "资源不存在"),
    RESOURCE_ALREADY_EXISTS(1001, "资源已存在"),
    INVALID_PARAMETER(1002, "参数无效"),
    OPERATION_NOT_ALLOWED(1003, "操作不允许"),
    
    // ==================== 字典相关 (1100-1199) ====================
    DICT_NOT_FOUND(1100, "字典不存在"),
    DICT_CODE_DUPLICATE(1101, "字典编码重复"),
    DICT_TYPE_INVALID(1102, "字典类型无效"),
    DICT_ITEM_NOT_FOUND(1103, "字典项不存在"),
    
    // ==================== 字段相关 (1200-1299) ====================
    FIELD_NOT_FOUND(1200, "字段不存在"),
    FIELD_KEY_DUPLICATE(1201, "字段键重复"),
    FIELD_TYPE_INVALID(1202, "字段类型无效"),
    
    // ==================== 模型相关 (1300-1399) ====================
    MODEL_NOT_FOUND(1300, "模型不存在"),
    MODEL_CODE_DUPLICATE(1301, "模型编码重复"),
    MODEL_FIELD_NOT_BOUND(1302, "字段未绑定到模型"),
    MODEL_FIELD_ALREADY_BOUND(1303, "字段已绑定到模型"),
    
    // ==================== 页面相关 (1400-1499) ====================
    PAGE_NOT_FOUND(1400, "页面不存在"),
    PAGE_NAME_DUPLICATE(1401, "页面名称重复"),
    PAGE_SCHEMA_INVALID(1402, "页面Schema无效"),
    
    // ==================== 动态数据相关 (1500-1599) ====================
    DYNAMIC_RECORD_NOT_FOUND(1500, "记录不存在"),
    DYNAMIC_VALIDATION_FAILED(1501, "数据验证失败"),
    DYNAMIC_QUERY_FAILED(1502, "查询失败"),

    // ==================== Event Store 相关 (1600-1699) ====================
    EVENT_VERSION_CONFLICT(1600, "事件版本冲突"),
    EVENT_STORE_APPEND_FAILED(1601, "事件追加失败"),
    EVENT_REPLAY_FAILED(1602, "事件重放失败"),
    EVENT_SNAPSHOT_FAILED(1603, "快照创建失败"),

    // ==================== State Graph 相关 (1700-1799) ====================
    STATE_GRAPH_NOT_FOUND(1700, "状态机定义不存在"),
    STATE_TRANSITION_DENIED(1701, "状态转移被拒绝"),
    STATE_GUARD_FAILED(1702, "状态转移前置条件不满足"),
    STATE_GRAPH_INVALID(1703, "状态机定义无效"),
    STATE_NODE_NOT_FOUND(1704, "状态节点不存在"),

    // ==================== Decision 相关 (1800-1899) ====================
    DECISION_DEF_NOT_FOUND(1800, "裁决定义不存在"),
    EVIDENCE_INCOMPLETE(1801, "证据不完整"),
    INVARIANT_VIOLATION(1802, "不变式违反"),
    DECISION_ALREADY_EXISTS(1803, "裁决已存在"),
    INVALID_OUTCOME(1804, "无效的裁决结果"),
    DECISION_BLOCKED(1805, "裁决被阻止"),

    // ==================== Git-First 相关 (1900-1999) ====================
    GIT_FIRST_VIOLATION(1900, "违反 Git-First 规则"),
    GIT_COMMIT_FAILED(1901, "Git 提交失败"),
    GIT_RELEASE_FAILED(1902, "Release 处理失败"),
    
    // ==================== 租户相关 (2000-2099) ====================
    TENANT_CONTEXT_MISSING(2000, "租户上下文缺失"),
    TENANT_NOT_FOUND(2001, "租户不存在"),
    TENANT_ISOLATION_VIOLATION(2002, "违反租户隔离规则");
    
    private final int code;
    private final String message;
}
