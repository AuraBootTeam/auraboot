package com.auraboot.framework.application.database.mybatis;

import com.auraboot.framework.meta.entity.payload.UiPayload;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

/**
 * UiMetaBean类型处理器
 * 用于处理数据库JSONB字段与UiMetaBean对象之间的转换
 *
 * @author AuraBoot Team
 * @since 1.0.0
 */
@Component
@MappedTypes({UiPayload.class})
@MappedJdbcTypes({JdbcType.OTHER}) // PostgreSQL JSONB类型对应JdbcType.OTHER
public class UiPayloadTypeHandler extends GenericJacksonTypeHandler<UiPayload> {

    public UiPayloadTypeHandler() {
        super(UiPayload.class);
    }
}