package com.auraboot.framework.application.database.mybatis;

import com.auraboot.framework.meta.entity.payload.FieldRefTargetBean;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

/**
 * FieldRefTargetBean类型处理器
 * 用于处理数据库JSONB字段与FieldRefTargetBean对象之间的转换
 *
 * @author AuraBoot Team
 * @since 1.0.0
 */
@Component
@MappedTypes({FieldRefTargetBean.class})
@MappedJdbcTypes({JdbcType.OTHER}) // PostgreSQL JSONB类型对应JdbcType.OTHER
public class FieldRefTargetBeanTypeHandler extends GenericJacksonTypeHandler<FieldRefTargetBean> {

    public FieldRefTargetBeanTypeHandler() {
        super(FieldRefTargetBean.class);
    }
}