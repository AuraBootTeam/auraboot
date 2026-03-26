package com.auraboot.framework.meta.security;

import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.dto.QuerySecurityValidationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Pattern;

/**
 * SQL注入防护组件
 * 提供SQL注入检测和防护功能
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Slf4j
@Component
public class SqlInjectionProtector {

    /**
     * 危险SQL模式列表
     */
    private static final List<Pattern> DANGEROUS_PATTERNS = Arrays.asList(
        // SQL关键字注入
        Pattern.compile("(?i).*\\b(union|select|insert|update|delete|drop|create|alter|exec|execute)\\b.*"),
        // 注释注入
        Pattern.compile("(?i).*(--|#|/\\*|\\*/).*"),
        // 引号注入
        Pattern.compile("(?i).*['\";].*"),
        // 存储过程注入
        Pattern.compile("(?i).*\\b(sp_|xp_)\\w+.*"),
        // 函数注入
        Pattern.compile("(?i).*\\b(char|ascii|substring|length|concat|cast|convert)\\s*\\(.*"),
        // 逻辑操作符注入
        Pattern.compile("(?i).*(\\bor\\b|\\band\\b)\\s+\\d+\\s*=\\s*\\d+.*"),
        // 时间延迟注入
        Pattern.compile("(?i).*\\b(waitfor|delay|sleep|benchmark)\\b.*"),
        // 信息泄露注入
        Pattern.compile("(?i).*\\b(information_schema|sys\\.|pg_|mysql\\.).*")
    );

    /**
     * 白名单字符模式
     */
    private static final Pattern SAFE_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-\\.\\s]+$");

    /**
     * 最大字符串长度
     */
    private static final int MAX_STRING_LENGTH = 1000;

    /**
     * 验证查询条件的安全性
     * @param conditions 查询条件列表
     * @return 验证结果
     */
    public QuerySecurityValidationResult validateQueryConditions(List<QueryCondition> conditions) {
        log.debug("开始验证查询条件安全性，条件数量: {}", conditions != null ? conditions.size() : 0);
        
        QuerySecurityValidationResult result = new QuerySecurityValidationResult();
        result.setValid(true);
        result.setErrors(new ArrayList<>());
        result.setWarnings(new ArrayList<>());
        result.setSecurityIssues(new ArrayList<>());
        result.setRiskLevel(QuerySecurityValidationResult.SecurityRiskLevel.LOW);
        
        long startTime = System.currentTimeMillis();
        
        if (conditions == null || conditions.isEmpty()) {
            result.setValidationTimeMs(System.currentTimeMillis() - startTime);
            return result;
        }
        
        for (QueryCondition condition : conditions) {
            validateSingleCondition(condition, result);
        }
        
        // 设置最终的风险等级
        if (!result.getSecurityIssues().isEmpty()) {
            QuerySecurityValidationResult.SecurityRiskLevel maxRisk = result.getSecurityIssues().stream()
                .map(QuerySecurityValidationResult.SecurityIssue::getSeverity)
                .max(Comparator.comparing(Enum::ordinal))
                .orElse(QuerySecurityValidationResult.SecurityRiskLevel.LOW);
            result.setRiskLevel(maxRisk);
        }
        
        result.setValidationTimeMs(System.currentTimeMillis() - startTime);
        
        log.debug("查询条件安全性验证完成，结果: valid={}, riskLevel={}, issues={}", 
                 result.getValid(), result.getRiskLevel(), result.getSecurityIssues().size());
        
        return result;
    }

    /**
     * 验证单个查询条件
     * @param condition 查询条件
     * @param result 验证结果
     */
    private void validateSingleCondition(QueryCondition condition, QuerySecurityValidationResult result) {
        if (condition == null) {
            return;
        }
        
        // 验证字段名
        validateFieldName(condition.getFieldName(), result);
        
        // 验证操作符
        validateOperator(condition.getOperator().name(), result);
        
        // 验证值
        validateValue(condition.getValue(), condition.getFieldName(), result);
    }

    /**
     * 验证字段名
     * @param fieldName 字段名
     * @param result 验证结果
     */
    private void validateFieldName(String fieldName, QuerySecurityValidationResult result) {
        if (!StringUtils.hasText(fieldName)) {
            addError(result, "字段名不能为空");
            return;
        }
        
        // 检查字段名长度
        if (fieldName.length() > 100) {
            addSecurityIssue(result, "FIELD_NAME_TOO_LONG", 
                           "字段名过长，可能存在注入风险", fieldName, null,
                           QuerySecurityValidationResult.SecurityRiskLevel.MEDIUM);
        }
        
        // 检查字段名是否包含危险字符
        if (!SAFE_PATTERN.matcher(fieldName).matches()) {
            addSecurityIssue(result, "UNSAFE_FIELD_NAME", 
                           "字段名包含不安全字符", fieldName, fieldName,
                           QuerySecurityValidationResult.SecurityRiskLevel.HIGH);
        }
        
        // 检查是否为SQL关键字
        if (isSqlKeyword(fieldName)) {
            addSecurityIssue(result, "SQL_KEYWORD_FIELD", 
                           "字段名为SQL关键字", fieldName, fieldName,
                           QuerySecurityValidationResult.SecurityRiskLevel.MEDIUM);
        }
    }

    /**
     * 验证操作符
     * @param operator 操作符
     * @param result 验证结果
     */
    private void validateOperator(String operator, QuerySecurityValidationResult result) {
        if (!StringUtils.hasText(operator)) {
            addError(result, "操作符不能为空");
            return;
        }

        // 允许的操作符列表 - 包括 SQL 符号和枚举名称
        Set<String> allowedOperators = Set.of(
            // SQL 符号
            "=", "!=", "<>", ">", ">=", "<", "<=",
            "LIKE", "NOT LIKE", "IN", "NOT IN",
            "IS NULL", "IS NOT NULL", "BETWEEN", "NOT BETWEEN",
            // QueryCondition.Operator 枚举名称
            "EQ", "NE", "GT", "GE", "LT", "LE",
            "NOT_LIKE", "NOT_IN", "IS_NULL", "IS_NOT_NULL", "NOT_BETWEEN"
        );

        if (!allowedOperators.contains(operator.toUpperCase())) {
            addSecurityIssue(result, "INVALID_OPERATOR",
                           "不支持的操作符", "operator", operator,
                           QuerySecurityValidationResult.SecurityRiskLevel.HIGH);
        }
    }

    /**
     * 验证值
     * @param value 值
     * @param fieldName 字段名
     * @param result 验证结果
     */
    private void validateValue(Object value, String fieldName, QuerySecurityValidationResult result) {
        if (value == null) {
            return;
        }
        
        String stringValue = value.toString();
        
        // 检查值的长度
        if (stringValue.length() > MAX_STRING_LENGTH) {
            addSecurityIssue(result, "VALUE_TOO_LONG", 
                           "查询值过长，可能存在注入风险", fieldName, stringValue,
                           QuerySecurityValidationResult.SecurityRiskLevel.MEDIUM);
        }
        
        // 检查危险模式
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(stringValue).matches()) {
                addSecurityIssue(result, "SQL_INJECTION_PATTERN", 
                               "检测到潜在的SQL注入模式", fieldName, stringValue,
                               QuerySecurityValidationResult.SecurityRiskLevel.CRITICAL);
                break;
            }
        }
        
        // 检查特殊字符
        if (containsSpecialCharacters(stringValue)) {
            addWarning(result, "查询值包含特殊字符，请确认是否为正常业务需求: " + fieldName);
        }
    }

    /**
     * 检查是否为SQL关键字
     * @param word 单词
     * @return 是否为SQL关键字
     */
    private boolean isSqlKeyword(String word) {
        Set<String> sqlKeywords = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "ALTER", 
            "TABLE", "INDEX", "VIEW", "DATABASE", "SCHEMA", "GRANT", "REVOKE",
            "FROM", "WHERE", "JOIN", "INNER", "LEFT", "RIGHT", "OUTER", "ON",
            "GROUP", "ORDER", "BY", "HAVING", "LIMIT", "OFFSET", "UNION", "ALL"
        );
        return sqlKeywords.contains(word.toUpperCase());
    }

    /**
     * 检查是否包含特殊字符
     * @param value 值
     * @return 是否包含特殊字符
     */
    private boolean containsSpecialCharacters(String value) {
        return value.matches(".*[<>\"'&;\\\\].*");
    }

    /**
     * 添加错误信息
     * @param result 验证结果
     * @param error 错误信息
     */
    private void addError(QuerySecurityValidationResult result, String error) {
        result.setValid(false);
        result.getErrors().add(error);
    }

    /**
     * 添加警告信息
     * @param result 验证结果
     * @param warning 警告信息
     */
    private void addWarning(QuerySecurityValidationResult result, String warning) {
        result.getWarnings().add(warning);
    }

    /**
     * 添加安全问题
     * @param result 验证结果
     * @param type 问题类型
     * @param description 问题描述
     * @param field 字段名
     * @param value 值
     * @param severity 严重程度
     */
    private void addSecurityIssue(QuerySecurityValidationResult result, String type, 
                                 String description, String field, String value,
                                 QuerySecurityValidationResult.SecurityRiskLevel severity) {
        QuerySecurityValidationResult.SecurityIssue issue = new QuerySecurityValidationResult.SecurityIssue();
        issue.setType(type);
        issue.setDescription(description);
        issue.setField(field);
        issue.setValue(value);
        issue.setSeverity(severity);
        
        result.getSecurityIssues().add(issue);
        
        // 如果是严重或关键问题，设置验证失败
        if (severity == QuerySecurityValidationResult.SecurityRiskLevel.CRITICAL ||
            severity == QuerySecurityValidationResult.SecurityRiskLevel.HIGH) {
            result.setValid(false);
            result.getErrors().add(description + ": " + field);
        }
    }

    /**
     * 清理和转义字符串值
     * @param value 原始值
     * @return 清理后的值
     */
    public String sanitizeValue(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        
        // 移除危险字符
        String sanitized = value.replaceAll("['\";\\\\]", "");
        
        // 限制长度
        if (sanitized.length() > MAX_STRING_LENGTH) {
            sanitized = sanitized.substring(0, MAX_STRING_LENGTH);
        }
        
        return sanitized;
    }

    /**
     * 检查字符串是否安全
     * @param value 要检查的值
     * @return 是否安全
     */
    public boolean isSafeValue(String value) {
        if (!StringUtils.hasText(value)) {
            return true;
        }
        
        // 检查长度
        if (value.length() > MAX_STRING_LENGTH) {
            return false;
        }
        
        // 检查危险模式
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(value).matches()) {
                return false;
            }
        }
        
        return true;
    }
}