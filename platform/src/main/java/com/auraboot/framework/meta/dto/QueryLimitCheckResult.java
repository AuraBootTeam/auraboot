package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.List;

/**
 * 查询限制检查结果DTO
 */
@Data
public class QueryLimitCheckResult {

    /**
     * 是否通过检查
     */
    private Boolean valid;

    /**
     * 违规信息列表
     */
    private List<String> violations;

    /**
     * 检查详情
     */
    private CheckDetails details;

    /**
     * 检查详情
     */
    @Data
    public static class CheckDetails {
        private Boolean maxRecordsValid;
        private Boolean timeoutValid;
        private Boolean complexityValid;
        private Boolean resourceValid;
    }
}