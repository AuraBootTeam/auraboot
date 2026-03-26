package com.auraboot.framework.meta.entity.deprecated;

import com.auraboot.framework.meta.entity.common.AbstractEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.auraboot.framework.meta.entity.payload.InstanceDataBean;
import com.auraboot.framework.application.database.mybatis.InstanceDataBeanTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 实体记录实体类
 * 对应表：ab_entity_record
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ab_entity_record", autoResultMap = true)
public class InstanceEntity extends AbstractEntity {
    
    @TableField("entity_code")
    private String entityCode;
    
    @TableField("entity_version")
    private Integer entityVersion;
    
    @TableField("form_code")
    private String formCode;
    
    @TableField("form_version")
    private Integer formVersion;
    
    /**
     * 实例数据
     */
    @TableField(typeHandler = InstanceDataBeanTypeHandler.class)
    private InstanceDataBean data;

}