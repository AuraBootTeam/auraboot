package com.auraboot.framework.application.typehandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
    
    private static final ObjectMapper PRECISE_JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    @Override
    protected ObjectMapper getObjectMapper() {
        return PRECISE_JSON;
    }

    public JsonNodeTypeHandler() {
        super(JsonNode.class);
    }
}
