package com.auraboot.framework.application.database.mybatis;

import com.auraboot.framework.meta.entity.payload.FieldQuerySchemaBean;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

/**
 * FieldQuerySchemaBean类型处理器
 * 用于处理数据库JSONB字段与FieldQuerySchemaBean对象之间的转换
 *
 * @author AuraBoot Team
 * @since 1.0.0
 */
@Component
@MappedTypes({FieldQuerySchemaBean.class})
@MappedJdbcTypes({JdbcType.OTHER}) // PostgreSQL JSONB类型对应JdbcType.OTHER
public class FieldQuerySchemaBeanTypeHandler extends GenericJacksonTypeHandler<FieldQuerySchemaBean> {

    public FieldQuerySchemaBeanTypeHandler() {
        super(FieldQuerySchemaBean.class);
    }
}