package com.auraboot.framework.meta.entity.common;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 版本化实体类
 * 包含版本控制相关字段
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class AbstractMultiVersionEntity extends AbstractEntity {
    
    @TableField("version")
    private Integer version;

    @TableField("semver")
    private String semver;

    @TableField("row_version")
    private Integer rowVersion;
    
    @TableField("is_current")
    private Boolean isCurrent;
}