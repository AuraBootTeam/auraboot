package com.auraboot.framework.application.database.mybatis;

import java.util.List;

/**
 * PostgreSQL JSONB handler for arrays whose elements are structured JSON objects.
 */
public class JsonbObjectListTypeHandler extends GenericJavaTypeJacksonTypeHandler<List<Object>> {

    public JsonbObjectListTypeHandler() {
        super(objectMapper.getTypeFactory().constructCollectionType(List.class, Object.class));
    }
}
