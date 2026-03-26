package com.auraboot.framework.application.database.mybatis;

import com.auraboot.framework.meta.entity.payload.PageDefinitionBean;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

/**
 * PageDefinitionBean类型处理器
 * 用于处理PageDefinitionBean与数据库JSONB字段之间的转换
 */

@MappedTypes(PageDefinitionBean.class)
@MappedJdbcTypes(JdbcType.OTHER)
@Component
public class PageDefinitionBeanTypeHandler extends GenericJacksonTypeHandler<PageDefinitionBean> {
    
    public PageDefinitionBeanTypeHandler() {
        super(PageDefinitionBean.class);
    }
}