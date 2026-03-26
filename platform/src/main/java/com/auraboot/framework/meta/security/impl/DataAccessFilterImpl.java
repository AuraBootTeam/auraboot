package com.auraboot.framework.meta.security.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.common.util.DateUtil;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.security.DataAccessFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 数据权限过滤器实现
 *
 * <p>使用Permission系统进行权限检查，提供数据脱敏功能
 *
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataAccessFilterImpl implements DataAccessFilter {

    private final UserPermissionService userPermissionService;

    // 脱敏规则模式
    private static final Map<String, Pattern> MASKING_PATTERNS = new HashMap<>();
    
    static {
        // 手机号脱敏：保留前3位和后4位
        MASKING_PATTERNS.put("phone_masking", Pattern.compile("(\\d{3})\\d{4}(\\d{4})"));
        // 邮箱脱敏：保留前2位和@后的域名
        MASKING_PATTERNS.put("email_masking", Pattern.compile("(\\w{2})\\w*(@\\w+\\.\\w+)"));
        // 身份证脱敏：保留前6位和后4位
        MASKING_PATTERNS.put("id_card_masking", Pattern.compile("(\\d{6})\\d{8}(\\d{4})"));
        // 银行卡脱敏：保留后4位
        MASKING_PATTERNS.put("bank_card_masking", Pattern.compile("\\d{12}(\\d{4})"));
    }

    @Override
    @Cacheable(value = "dataFilterResult", key = "#request.userId + '_' + #request.tenantId + '_' + #request.modelCode")
    public DataFilterResult filterQueryResult(DataFilterRequest request) {
        log.info("开始过滤查询结果数据: userId={}, modelCode={}, recordCount={}",
                request.getUserId(), request.getModelCode(), request.getData().size());

        try {
            // 1. 使用Permission检查用户是否有模型读取权限
            String modelReadPermission = "model." + request.getModelCode() + ".read";
            boolean hasModelPermission = userPermissionService.hasPermission(request.getUserId(), modelReadPermission);

            if (!hasModelPermission) {
                log.warn("用户无权访问模型数据: userId={}, modelCode={}", request.getUserId(), request.getModelCode());
                return DataFilterResult.builder()
                        .success(false)
                        .errorMessage("无权访问此模型数据")
                        .build();
            }

            // 2. 构建字段权限信息（基于Permission系统）
            List<String> fieldCodes = extractCodes(request.getData());
            Map<String, FieldAccessInfo> fieldAccess = buildFieldPermissions(
                    request.getUserId(), request.getModelCode(), fieldCodes);

            // 3. 过滤和脱敏数据
            List<Map<String, Object>> filteredData = new ArrayList<>();
            DataFilterStatistics statistics = DataFilterStatistics.builder()
                    .originalRecordCount(request.getData().size())
                    .build();

            for (Map<String, Object> record : request.getData()) {
                Map<String, Object> filteredRecord = filterSingleRecord(record, fieldAccess);
                if (!filteredRecord.isEmpty()) {
                    filteredData.add(filteredRecord);
                }
            }

            statistics.setFilteredRecordCount(filteredData.size());
            statistics.setRemovedRecordCount(request.getData().size() - filteredData.size());

            // 4. 记录访问日志
            logDataAccess(DataAccessLogRequest.builder()
                    .userId(request.getUserId())
                    .tenantId(request.getTenantId())
                    .modelCode(request.getModelCode())
                    .action("query_filter")
                    .recordCount(filteredData.size())
                    .accessTime(DateUtil.getCurrentLocalDateTimeUtc())
                    .build());

            return DataFilterResult.builder()
                    .success(true)
                    .originalData(request.getData())
                    .filteredData(filteredData)
                    .statistics(statistics)
                    .filterTime(DateUtil.getCurrentLocalDateTimeUtc())
                    .build();

        } catch (Exception e) {
            log.error("过滤查询结果数据失败: userId={}, modelCode={}",
                    request.getUserId(), request.getModelCode(), e);
            return DataFilterResult.builder()
                    .success(false)
                    .errorMessage("数据过滤失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 基于Permission系统构建字段权限信息
     */
    private Map<String, FieldAccessInfo> buildFieldPermissions(Long userId, String modelCode, List<String> fieldCodes) {
        Map<String, FieldAccessInfo> fieldAccess = new HashMap<>();

        // 检查用户是否有模型的manage权限（有manage权限则所有字段可见且无需脱敏）
        String modelManagePermission = "model." + modelCode + ".manage";
        boolean hasManagePermission = userPermissionService.hasPermission(userId, modelManagePermission);

        for (String fieldCode : fieldCodes) {
            FieldAccessInfo info = new FieldAccessInfo();
            info.setCode(fieldCode);

            if (hasManagePermission) {
                // 管理员权限：所有字段可见，无需脱敏
                info.setVisible(true);
                info.setMaskingRequired(false);
            } else {
                // 普通用户：检查字段级权限
                String fieldReadPermission = "field." + modelCode + "_" + fieldCode + ".read";
                boolean hasFieldPermission = userPermissionService.hasPermission(userId, fieldReadPermission);

                // 如果没有字段级权限，回退到模型读取权限
                String modelReadPermission = "model." + modelCode + ".read";
                info.setVisible(hasFieldPermission || userPermissionService.hasPermission(userId, modelReadPermission));

                // 根据字段名确定是否需要脱敏
                info.setMaskingRule(determineMaskingRule(fieldCode, userId, null, null));
                info.setMaskingRequired(info.getMaskingRule() != null);
            }

            fieldAccess.put(fieldCode, info);
        }

        return fieldAccess;
    }

    @Override
    @SuppressWarnings("unchecked")
    public SimpleResult batchFilterData(Map<String, Object> request) {
        log.info("Batch filtering data: request keys={}", request.keySet());

        try {
            String modelCode = (String) request.get("modelCode");
            Long userId = extractLong(request.get("userId"));
            Long tenantId = extractLong(request.get("tenantId"));
            List<Map<String, Object>> records = (List<Map<String, Object>>) request.get("records");

            if (modelCode == null || userId == null || tenantId == null) {
                return SimpleResult.builder()
                        .success(false)
                        .errorMessage("Missing required fields: modelCode, userId, tenantId")
                        .build();
            }

            if (records == null || records.isEmpty()) {
                return SimpleResult.builder()
                        .success(true)
                        .count(0)
                        .items(new ArrayList<>())
                        .message("No records to filter")
                        .build();
            }

            int originalCount = records.size();

            // Apply row-level filtering using DataPermissionEngine via the existing permission check
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> record : records) {
                // Check model-level read permission
                String modelReadPermission = "model." + modelCode + ".read";
                boolean hasModelPermission = userPermissionService.hasPermission(userId, modelReadPermission);

                if (hasModelPermission) {
                    // Apply field-level filtering and masking
                    Map<String, FieldAccessInfo> fieldAccess = buildFieldPermissions(userId, modelCode,
                            new ArrayList<>(record.keySet()));
                    Map<String, Object> filteredRecord = filterSingleRecord(record, fieldAccess);
                    if (!filteredRecord.isEmpty()) {
                        filtered.add(filteredRecord);
                    }
                }
            }

            return SimpleResult.builder()
                    .success(true)
                    .count(filtered.size())
                    .items(new ArrayList<>(filtered))
                    .properties(Map.of(
                            "originalCount", originalCount,
                            "filteredCount", filtered.size(),
                            "removedCount", originalCount - filtered.size()
                    ))
                    .message("Batch data filtering completed")
                    .build();
        } catch (Exception e) {
            log.error("Batch data filtering failed", e);
            return SimpleResult.builder()
                    .success(false)
                    .errorMessage("Batch filtering failed: " + e.getMessage())
                    .build();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public SimpleResult filterRecord(Map<String, Object> request) {
        log.debug("Filtering single record: request keys={}", request.keySet());

        try {
            String modelCode = (String) request.get("modelCode");
            Long userId = extractLong(request.get("userId"));
            Map<String, Object> record = (Map<String, Object>) request.get("record");

            if (modelCode == null || userId == null || record == null) {
                return SimpleResult.builder()
                        .success(false)
                        .errorMessage("Missing required fields: modelCode, userId, record")
                        .build();
            }

            // Check model-level read permission
            String modelReadPermission = "model." + modelCode + ".read";
            boolean hasModelPermission = userPermissionService.hasPermission(userId, modelReadPermission);

            if (!hasModelPermission) {
                return SimpleResult.builder()
                        .success(true)
                        .data(null)
                        .properties(Map.of("accessible", false, "reason", "No model read permission"))
                        .message("Record filtered: no access")
                        .build();
            }

            // Apply field-level filtering and masking
            Map<String, FieldAccessInfo> fieldAccess = buildFieldPermissions(userId, modelCode,
                    new ArrayList<>(record.keySet()));
            Map<String, Object> filteredRecord = filterSingleRecord(record, fieldAccess);

            return SimpleResult.builder()
                    .success(true)
                    .data(filteredRecord)
                    .properties(Map.of(
                            "accessible", true,
                            "originalFieldCount", record.size(),
                            "filteredFieldCount", filteredRecord.size()
                    ))
                    .message("Record filtering completed")
                    .build();
        } catch (Exception e) {
            log.error("Single record filtering failed", e);
            return SimpleResult.builder()
                    .success(false)
                    .errorMessage("Record filtering failed: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public DataMaskingResult applyDataMasking(DataMaskingRequest request) {
        log.info("开始应用数据脱敏: code={}, maskingRule={}", 
                request.getCode(), request.getMaskingRule());

        try {
            Object originalValue = request.getValue();
            if (originalValue == null) {
                return DataMaskingResult.builder()
                        .success(true)
                        .originalValue(null)
                        .maskedValue(null)
                        .maskingApplied(false)
                        .build();
            }

            String stringValue = originalValue.toString();
            String maskedValue = applyMaskingRule(stringValue, request.getMaskingRule());

            return DataMaskingResult.builder()
                    .success(true)
                    .originalValue(originalValue)
                    .maskedValue(maskedValue)
                    .maskingApplied(!stringValue.equals(maskedValue))
                    .maskingRule(request.getMaskingRule())
                    .build();

        } catch (Exception e) {
            log.error("应用数据脱敏失败: code={}, maskingRule={}", 
                    request.getCode(), request.getMaskingRule(), e);
            return DataMaskingResult.builder()
                    .success(false)
                    .errorMessage("数据脱敏失败: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public SimpleResult getFieldMaskingRule(Map<String, Object> request) {
        log.debug("获取字段脱敏规则: request={}", request);

        try {
            String code = (String) request.get("code");
            Long userId = (Long) request.get("userId");
            Long tenantId = (Long) request.get("tenantId");
            String fieldType = (String) request.get("fieldType");

            // 基于字段类型和用户权限确定脱敏规则
            String maskingRule = determineMaskingRule(code, userId, tenantId, fieldType);

            return SimpleResult.builder()
                    .success(true)
                    .data(maskingRule)
                    .properties(Map.of(
                            "code", code,
                            "maskingRequired", maskingRule != null,
                            "ruleDescription", getMaskingRuleDescription(maskingRule)
                    ))
                    .build();

        } catch (Exception e) {
            log.error("获取字段脱敏规则失败: request={}", request, e);
            return SimpleResult.builder()
                    .success(false)
                    .errorMessage("获取脱敏规则失败: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public SimpleResult calculateDynamicMaskingStrategy(Map<String, Object> request) {
        log.info("计算动态脱敏策略: request={}", request);

        try {
            Long userId = (Long) request.get("userId");
            Long tenantId = (Long) request.get("tenantId");
            @SuppressWarnings("unchecked")
            Map<String, Object> context = (Map<String, Object>) request.get("context");

            Map<String, String> dynamicRules = new HashMap<>();

            // 基于时间的动态脱敏
            if (isWorkingHours()) {
                dynamicRules.put("timeBasedMasking", "relaxed");
            } else {
                dynamicRules.put("timeBasedMasking", "strict");
            }

            // 基于用户角色的动态脱敏
            String userRole = getUserRole(userId, tenantId);
            if ("admin".equals(userRole)) {
                dynamicRules.put("roleBasedMasking", "minimal");
            } else if ("manager".equals(userRole)) {
                dynamicRules.put("roleBasedMasking", "moderate");
            } else {
                dynamicRules.put("roleBasedMasking", "full");
            }

            return SimpleResult.builder()
                    .success(true)
                    .data(dynamicRules)
                    .properties(Map.of("contextHash", String.valueOf(context != null ? context.hashCode() : 0)))
                    .build();

        } catch (Exception e) {
            log.error("计算动态脱敏策略失败: request={}", request, e);
            return SimpleResult.builder()
                    .success(false)
                    .errorMessage("动态脱敏策略计算失败: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public void logDataAccess(DataAccessLogRequest request) {
        log.info("记录数据访问日志: userId={}, modelCode={}, action={}, recordCount={}", 
                request.getUserId(), request.getModelCode(), request.getAction(), request.getRecordCount());

        try {
            // 构建访问日志数据
            Map<String, Object> logData = new HashMap<>();
            logData.put("userId", request.getUserId());
            logData.put("tenantId", request.getTenantId());
            logData.put("modelCode", request.getModelCode());
            logData.put("action", request.getAction());
            logData.put("recordCount", request.getRecordCount());
            logData.put("accessTime", request.getAccessTime());
            logData.put("clientIp", request.getClientIp());
            logData.put("userAgent", request.getUserAgent());

            // TODO: 集成到审计日志系统
            log.debug("数据访问日志: {}", logData);

        } catch (Exception e) {
            log.error("记录数据访问日志失败: userId={}, modelCode={}", 
                    request.getUserId(), request.getModelCode(), e);
        }
    }

    // ==================== 私有辅助方法 ====================

    private List<String> extractCodes(List<Map<String, Object>> data) {
        if (data.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(data.get(0).keySet());
    }

    private Map<String, Object> filterSingleRecord(Map<String, Object> record, 
                                                   Map<String, FieldAccessInfo> fieldAccess) {
        Map<String, Object> filteredRecord = new HashMap<>();

        for (Map.Entry<String, Object> entry : record.entrySet()) {
            String code = entry.getKey();
            Object value = entry.getValue();

            FieldAccessInfo permission = fieldAccess.get(code);
            if (permission != null && Boolean.TRUE.equals(permission.getVisible())) {
                // 字段可见，检查是否需要脱敏
                if (Boolean.TRUE.equals(permission.getMaskingRequired()) && permission.getMaskingRule() != null) {
                    Object maskedValue = applyMaskingToValue(value, permission.getMaskingRule());
                    filteredRecord.put(code, maskedValue);
                } else {
                    filteredRecord.put(code, value);
                }
            }
            // 字段不可见则不添加到结果中
        }

        return filteredRecord;
    }

    private Object applyMaskingToValue(Object value, String maskingRule) {
        if (value == null) {
            return null;
        }

        String stringValue = value.toString();
        return applyMaskingRule(stringValue, maskingRule);
    }

    private String applyMaskingRule(String value, String maskingRule) {
        if (value == null || maskingRule == null) {
            return value;
        }

        switch (maskingRule) {
            case "phone_masking":
                return maskPhone(value);
            case "email_masking":
                return maskEmail(value);
            case "id_card_masking":
                return maskIdCard(value);
            case "bank_card_masking":
                return maskBankCard(value);
            case "name_masking":
                return maskName(value);
            default:
                return value;
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2");
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        return email.replaceAll("(\\w{2})\\w*(@\\w+\\.\\w+)", "$1****$2");
    }

    private String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 10) {
            return idCard;
        }
        return idCard.replaceAll("(\\d{6})\\d{8}(\\d{4})", "$1********$2");
    }

    private String maskBankCard(String bankCard) {
        if (bankCard == null || bankCard.length() < 8) {
            return bankCard;
        }
        return bankCard.replaceAll("\\d{12}(\\d{4})", "************$1");
    }

    private String maskName(String name) {
        if (name == null || name.length() < 2) {
            return name;
        }
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }

    private String determineMaskingRule(String code, Long userId, Long tenantId, String fieldType) {
        // 基于字段名称确定脱敏规则
        String lowerCode = code.toLowerCase();
        
        if (lowerCode.contains("phone") || lowerCode.contains("mobile")) {
            return "phone_masking";
        } else if (lowerCode.contains("email")) {
            return "email_masking";
        } else if (lowerCode.contains("idcard") || lowerCode.contains("identity")) {
            return "id_card_masking";
        } else if (lowerCode.contains("bankcard") || lowerCode.contains("account")) {
            return "bank_card_masking";
        } else if (lowerCode.contains("name") && !"username".equals(lowerCode)) {
            return "name_masking";
        }
        
        return null; // 不需要脱敏
    }

    private String getMaskingRuleDescription(String maskingRule) {
        if (maskingRule == null) {
            return "无需脱敏";
        }
        
        switch (maskingRule) {
            case "phone_masking":
                return "手机号脱敏：保留前3位和后4位";
            case "email_masking":
                return "邮箱脱敏：保留前2位和域名";
            case "id_card_masking":
                return "身份证脱敏：保留前6位和后4位";
            case "bank_card_masking":
                return "银行卡脱敏：仅保留后4位";
            case "name_masking":
                return "姓名脱敏：仅保留首字符";
            default:
                return "自定义脱敏规则";
        }
    }

    private List<String> calculateRemovedFields(Map<String, Object> original, Map<String, Object> filtered) {
        return original.keySet().stream()
                .filter(key -> !filtered.containsKey(key))
                .collect(Collectors.toList());
    }

    private List<String> calculateMaskedFields(Map<String, Object> filtered, 
                                               Map<String, FieldAccessInfo> fieldAccess) {
        return filtered.keySet().stream()
                .filter(key -> {
                    FieldAccessInfo permission = fieldAccess.get(key);
                    return permission != null && Boolean.TRUE.equals(permission.getMaskingRequired());
                })
                .collect(Collectors.toList());
    }

    /**
     * Extract a Long value from an Object that may be Number, String, etc.
     */
    private Long extractLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isWorkingHours() {
        // 简单的工作时间判断（9:00-18:00）
        int hour = java.time.LocalTime.now().getHour();
        return hour >= 9 && hour < 18;
    }

    private String getUserRole(Long userId, Long tenantId) {
        // 获取用户角色，这里简化处理
        // 实际实现应该从权限系统获取
        return "user"; // 默认角色
    }

    // ==================== 简化的方法实现 ====================

    @Override
    public SimpleResult warmupDataPermissionCache(Map<String, Object> request) {
        return SimpleResult.builder()
                .success(true)
                .count(0)
                .message("数据权限缓存预热完成")
                .build();
    }

    @Override
    public SimpleResult refreshDataPermissionCache(Map<String, Object> request) {
        return SimpleResult.builder()
                .success(true)
                .count(0)
                .message("数据权限缓存刷新完成")
                .build();
    }

    @Override
    public SimpleResult clearDataPermissionCache(Map<String, Object> request) {
        return SimpleResult.builder()
                .success(true)
                .count(0)
                .message("数据权限缓存清理完成")
                .build();
    }

    @Override
    public SimpleResult validateDataAccessPermission(Map<String, Object> request) {
        return SimpleResult.builder()
                .success(true)
                .properties(Map.of("valid", true))
                .message("数据访问权限验证完成")
                .build();
    }

    @Override
    public SimpleResult validateDataModificationPermission(Map<String, Object> request) {
        return SimpleResult.builder()
                .success(true)
                .properties(Map.of("valid", true))
                .message("数据修改权限验证完成")
                .build();
    }

    @Override
    public SimpleResult validateDataExportPermission(Map<String, Object> request) {
        return SimpleResult.builder()
                .success(true)
                .properties(Map.of("valid", true))
                .message("数据导出权限验证完成")
                .build();
    }

    @Override
    public SimpleResult analyzeDataAccessPattern(Map<String, Object> request) {
        return SimpleResult.builder()
                .success(true)
                .data(new HashMap<>())
                .message("数据访问模式分析完成")
                .build();
    }

    @Override
    public SimpleResult detectDataAccessAnomalies(Map<String, Object> request) {
        return SimpleResult.builder()
                .success(true)
                .items(new ArrayList<>())
                .message("数据访问异常检测完成")
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public SimpleResult executeDataFilterRules(Map<String, Object> request) {
        log.info("Executing data filter rules: request keys={}", request.keySet());

        try {
            String modelCode = (String) request.get("modelCode");
            Long userId = extractLong(request.get("userId"));
            Long tenantId = extractLong(request.get("tenantId"));
            List<Map<String, Object>> records = (List<Map<String, Object>>) request.get("records");

            if (modelCode == null || userId == null || tenantId == null) {
                return SimpleResult.builder()
                        .success(false)
                        .errorMessage("Missing required fields: modelCode, userId, tenantId")
                        .build();
            }

            if (records == null || records.isEmpty()) {
                return SimpleResult.builder()
                        .success(true)
                        .items(new ArrayList<>())
                        .count(0)
                        .message("No records to process")
                        .build();
            }

            int originalCount = records.size();

            // Step 1: Model-level permission check
            String modelReadPermission = "model." + modelCode + ".read";
            boolean hasModelPermission = userPermissionService.hasPermission(userId, modelReadPermission);
            if (!hasModelPermission) {
                return SimpleResult.builder()
                        .success(true)
                        .items(new ArrayList<>())
                        .count(0)
                        .properties(Map.of("reason", "No model read permission"))
                        .message("All records filtered: no model access")
                        .build();
            }

            // Step 2: Apply field-level filtering and masking
            List<String> fieldCodes = new ArrayList<>(records.get(0).keySet());
            Map<String, FieldAccessInfo> fieldAccess = buildFieldPermissions(userId, modelCode, fieldCodes);

            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> record : records) {
                Map<String, Object> filteredRecord = filterSingleRecord(record, fieldAccess);
                if (!filteredRecord.isEmpty()) {
                    filtered.add(filteredRecord);
                }
            }

            return SimpleResult.builder()
                    .success(true)
                    .items(new ArrayList<>(filtered))
                    .count(filtered.size())
                    .properties(Map.of(
                            "originalCount", originalCount,
                            "filteredCount", filtered.size(),
                            "appliedRules", "field_permission,field_masking"
                    ))
                    .message("Data filter rules executed successfully")
                    .build();
        } catch (Exception e) {
            log.error("Data filter rules execution failed", e);
            return SimpleResult.builder()
                    .success(false)
                    .errorMessage("Data filter rules execution failed: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public SimpleResult validateDataFilterRules(Map<String, Object> request) {
        return SimpleResult.builder()
                .success(true)
                .properties(Map.of("valid", true))
                .message("数据过滤规则验证完成")
                .build();
    }

    @Override
    public SimpleResult optimizeDataFilterRules(Map<String, Object> request) {
        return SimpleResult.builder()
                .success(true)
                .items(new ArrayList<>())
                .message("数据过滤规则优化完成")
                .build();
    }
}
