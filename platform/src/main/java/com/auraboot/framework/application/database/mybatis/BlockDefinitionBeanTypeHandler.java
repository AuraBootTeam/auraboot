package com.auraboot.framework.application.database.mybatis;

import com.auraboot.framework.meta.entity.payload.BlockDefinitionBean;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

/**
 * BlockDefinitionBean类型处理器
 * 用于处理BlockEntity中的definition字段的JSONB类型转换
 */
@MappedTypes(BlockDefinitionBean.class)
@MappedJdbcTypes(JdbcType.OTHER)
@Component
public class BlockDefinitionBeanTypeHandler extends GenericJacksonTypeHandler<BlockDefinitionBean> {
    
    public BlockDefinitionBeanTypeHandler() {
        super(BlockDefinitionBean.class);
    }
}