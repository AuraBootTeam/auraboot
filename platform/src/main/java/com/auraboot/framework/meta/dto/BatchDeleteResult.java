package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 批量删除结果
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BatchDeleteResult extends BatchOperationResult {
    // 继承父类的所有字段，无需额外字段
}