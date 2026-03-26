package com.auraboot.framework.meta.entity.deprecated;

import com.auraboot.framework.application.database.mybatis.PageDefinitionBeanTypeHandler;
import com.auraboot.framework.meta.entity.common.AbstractMultiVersionEntity;
import com.auraboot.framework.meta.entity.payload.PageDefinitionBean;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 页面定义实体类
 * 对应表：ab_meta_page
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ab_meta_page", autoResultMap = true)
public class PageDefinitionEntity extends AbstractMultiVersionEntity {
    
    @TableField(value = "definition", typeHandler = PageDefinitionBeanTypeHandler.class)
    private PageDefinitionBean definition;

    @TableField("code")
    private String code;

}