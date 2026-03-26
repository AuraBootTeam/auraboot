package com.auraboot.framework.application.database.mybatis;

import com.auraboot.framework.meta.entity.payload.FieldUiSchemaBean;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

/**
 * FieldUiSchemaBean类型处理器
 * 用于处理数据库JSONB字段与FieldUiSchemaBean对象之间的转换
 *
 * @author AuraBoot Team
 * @since 1.0.0
 */
@Component
@MappedTypes({FieldUiSchemaBean.class})
@MappedJdbcTypes({JdbcType.OTHER}) // PostgreSQL JSONB类型对应JdbcType.OTHER
public class FieldUiSchemaBeanTypeHandler extends GenericJacksonTypeHandler<FieldUiSchemaBean> {

    public FieldUiSchemaBeanTypeHandler() {
        super(FieldUiSchemaBean.class);
    }
}