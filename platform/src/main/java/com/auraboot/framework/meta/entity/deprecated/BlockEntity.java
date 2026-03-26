package com.auraboot.framework.meta.entity.deprecated;

import com.auraboot.framework.meta.entity.common.AbstractMultiVersionEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.auraboot.framework.meta.entity.payload.BlockDefinitionBean;
import com.auraboot.framework.application.database.mybatis.BlockDefinitionBeanTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 表单定义实体类
 * 对应表：ab_meta_block
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ab_meta_block", autoResultMap = true)
public class BlockEntity extends AbstractMultiVersionEntity {
    
  
    @TableField("code")
    private String code;
    
    @TableField("type")
    private String type;

    /**
     * 区块定义
     */
    @TableField(typeHandler = BlockDefinitionBeanTypeHandler.class)
    private BlockDefinitionBean definition;

}