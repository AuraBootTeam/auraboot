package com.auraboot.framework.application.database.mybatis;

import com.auraboot.framework.meta.entity.payload.InstanceDataBean;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

/**
 * InstanceDataBean类型处理器
 * 用于处理InstanceEntity中的data字段的JSONB类型转换
 */
@MappedTypes(InstanceDataBean.class)
@MappedJdbcTypes(JdbcType.OTHER)
@Component

public class InstanceDataBeanTypeHandler extends GenericJacksonTypeHandler<InstanceDataBean> {
    
    public InstanceDataBeanTypeHandler() {
        super(InstanceDataBean.class);
    }
}