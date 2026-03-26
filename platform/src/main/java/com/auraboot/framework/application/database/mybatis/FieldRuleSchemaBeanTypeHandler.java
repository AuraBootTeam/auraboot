package com.auraboot.framework.application.database.mybatis;

import com.auraboot.framework.meta.entity.payload.FieldRuleSchemaBean;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

/**
 * FieldRuleSchemaBean类型处理器
 * 用于处理数据库JSONB字段与FieldRuleSchemaBean对象之间的转换
 *
 * @author AuraBoot Team
 * @since 1.0.0
 */
@Component
@MappedTypes({FieldRuleSchemaBean.class})
@MappedJdbcTypes({JdbcType.OTHER}) // PostgreSQL JSONB类型对应JdbcType.OTHER
public class FieldRuleSchemaBeanTypeHandler extends GenericJacksonTypeHandler<FieldRuleSchemaBean> {

    public FieldRuleSchemaBeanTypeHandler() {
        super(FieldRuleSchemaBean.class);
    }
}