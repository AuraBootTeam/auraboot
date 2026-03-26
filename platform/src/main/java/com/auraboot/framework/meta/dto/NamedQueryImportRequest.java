package com.auraboot.framework.meta.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 命名查询导入请求DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryImportRequest {

    /**
     * 导入文件路径或内容
     */
    @NotBlank(message = "导入文件路径或内容不能为空")
    private String importSource;

    /**
     * 导入类型
     */
    private String importType = "file"; // FILE, CONTENT, URL

    /**
     * 导入格式
     */
    private String importFormat = "json"; // JSON, XML, YAML, SQL



    /**
     * 导入模式
     */
    private String importMode = "create_or_update"; // CREATE_ONLY, UPDATE_ONLY, CREATE_OR_UPDATE, REPLACE

    /**
     * 冲突处理策略
     */
    private String conflictStrategy = "skip"; // SKIP, OVERWRITE, RENAME, MERGE

    /**
     * 是否验证导入数据
     */
    private Boolean validateData = true;

    /**
     * 是否导入字段定义
     */
    private Boolean importFields = true;

    /**
     * 是否导入版本历史
     */
    private Boolean importVersionHistory = false;

    /**
     * 是否导入权限信息
     */
    private Boolean importPermissions = false;

    /**
     * 是否导入依赖信息
     */
    private Boolean importDependencies = false;

    /**
     * 是否启用导入的查询
     */
    private Boolean enableImportedQueries = false;

    /**
     * 批量导入大小
     */
    private Integer batchSize = 100;

    /**
     * 是否继续处理错误
     */
    private Boolean continueOnError = true;

    /**
     * 最大错误数
     */
    private Integer maxErrors = 10;

    /**
     * 导入选项
     */
    private ImportOptions importOptions;

    /**
     * 导入备注
     */
    @Size(max = 500, message = "导入备注长度不能超过500个字符")
    private String importNotes;

    /**
     * 导入选项内部类
     */
    @Data
    public static class ImportOptions {
        /**
         * 字符编码
         */
        private String encoding = "UTF-8";

        /**
         * 是否忽略未知字段
         */
        private Boolean ignoreUnknownFields = true;

        /**
         * 是否严格模式
         */
        private Boolean strictMode = false;

        /**
         * 是否预览模式（不实际导入）
         */
        private Boolean previewMode = false;

        /**
         * 是否备份现有数据
         */
        private Boolean backupExisting = true;

        /**
         * 超时时间（秒）
         */
        private Integer timeoutSeconds = 300;

        /**
         * 是否异步导入
         */
        private Boolean asyncImport = false;

        /**
         * 通知邮箱
         */
        private String notificationEmail;
    }
}