package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 模式同步选项
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class SchemaSyncOptions {
    
    /**
     * 是否强制同步（忽略数据丢失风险）
     */
    @Builder.Default
    private Boolean forceSync = false;
    
    /**
     * 是否备份现有数据
     */
    @Builder.Default
    private Boolean backupData = true;
    
    /**
     * 是否创建索引
     */
    @Builder.Default
    private Boolean createIndexes = true;
    
    /**
     * 是否验证数据完整性
     */
    @Builder.Default
    private Boolean validateData = true;
    
    /**
     * 同步模式
     */
    @Builder.Default
    private SyncMode syncMode = SyncMode.SAFE;
    
    public enum SyncMode {
        SAFE,       // 安全模式，不会删除现有数据
        FORCE,      // 强制模式，可能会删除数据
        DRY_RUN     // 预览模式，只生成DDL不执行
    }
}