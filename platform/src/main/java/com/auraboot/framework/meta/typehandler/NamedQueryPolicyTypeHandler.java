package com.auraboot.framework.meta.typehandler;

import com.auraboot.framework.application.typehandler.GenericJacksonTypeHandler;
import com.auraboot.framework.meta.entity.NamedQueryPolicy;
import org.apache.ibatis.type.MappedTypes;

/**
 * MyBatis type handler for NamedQueryPolicy JSONB column.
 */
@MappedTypes(NamedQueryPolicy.class)
public class NamedQueryPolicyTypeHandler extends GenericJacksonTypeHandler<NamedQueryPolicy> {

    public NamedQueryPolicyTypeHandler() {
        super(NamedQueryPolicy.class);
    }
}
