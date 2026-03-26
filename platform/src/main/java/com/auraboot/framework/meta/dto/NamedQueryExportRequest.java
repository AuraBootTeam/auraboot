package com.auraboot.framework.meta.dto;

import lombok.Data;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 命名查询导出请求DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryExportRequest {

    /**
     * 要导出的查询ID列表
     */
    @NotEmpty(message = "查询ID列表不能为空")
    private List<Long> queryIds;

    /**
     * 导出格式
     */
    private String exportFormat = "json"; // JSON, XML, YAML, SQL

    /**
     * 是否包含字段定义
     */
    private Boolean includeFields = true;

    /**
     * 是否包含版本历史
     */
    private Boolean includeVersionHistory = false;

    /**
     * 是否包含统计信息
     */
    private Boolean includeStatistics = false;

    /**
     * 是否包含依赖信息
     */
    private Boolean includeDependencies = false;

    /**
     * 是否包含权限信息
     */
    private Boolean includePermissions = false;

    /**
     * 导出范围
     */
    private String exportScope = "selected"; // SELECTED, ALL





    /**
     * 状态过滤
     */
    private String status;

    /**
     * 导出文件名
     */
    @Size(max = 200, message = "文件名长度不能超过200个字符")
    private String fileName;

    /**
     * 是否压缩
     */
    private Boolean compress = false;

    /**
     * 压缩格式
     */
    private String compressionFormat = "zip"; // ZIP, GZIP, TAR

    /**
     * 导出选项
     */
    private ExportOptions exportOptions;

    /**
     * 导出备注
     */
    @Size(max = 500, message = "导出备注长度不能超过500个字符")
    private String exportNotes;

    /**
     * 导出选项内部类
     */
    @Data
    public static class ExportOptions {
        /**
         * 是否美化输出
         */
        private Boolean prettyPrint = true;

        /**
         * 字符编码
         */
        private String encoding = "UTF-8";

        /**
         * 是否包含元数据
         */
        private Boolean includeMetadata = true;

        /**
         * 是否包含时间戳
         */
        private Boolean includeTimestamp = true;

        /**
         * 是否包含导出者信息
         */
        private Boolean includeExporterInfo = true;

        /**
         * 最大文件大小（MB）
         */
        private Integer maxFileSizeMB = 100;

        /**
         * 是否分批导出
         */
        private Boolean batchExport = false;

        /**
         * 批次大小
         */
        private Integer batchSize = 100;
    }
}