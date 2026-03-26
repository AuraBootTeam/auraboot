package com.auraboot.framework.application.typehandler;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

/**
 * Jackson JsonNode类型处理器
 * 用于处理PostgreSQL的JSONB类型到JsonNode的转换
 */
@Component
@MappedTypes({JsonNode.class})
@MappedJdbcTypes({JdbcType.OTHER})
public class JsonNodeTypeHandler extends GenericJacksonTypeHandler<JsonNode> {
    
    public JsonNodeTypeHandler() {
        super(JsonNode.class);
    }
}
