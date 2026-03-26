package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 绑定关系导入请求DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class BindingImportRequest {

    /**
     * 导入数据
     */
    private Object importData;

    /**
     * 导入模式
     */
    private ImportMode importMode;

    /**
     * 是否覆盖现有数据
     */
    private Boolean overwrite;

    /**
     * 扩展信息
     */
    private Object extension;

    /**
     * 构造函数
     */
    public BindingImportRequest() {
        this.importMode = ImportMode.APPEND;
        this.overwrite = false;
    }

    /**
     * 导入模式
     */
    public enum ImportMode {
        APPEND,   // 追加
        REPLACE,  // 替换
        MERGE     // 合并
    }
}