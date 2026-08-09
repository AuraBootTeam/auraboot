package com.auraboot.framework.permission.constants;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class MetaPermissionContractTest {

    @Test
    void pageSchemaReadPermissionPreservesLegacyPageResourceCode() throws Exception {
        Field field;
        try {
            field = MetaPermission.class.getField("PAGE_SCHEMA_READ");
        } catch (NoSuchFieldException e) {
            fail("MetaPermission.PAGE_SCHEMA_READ must exist for legacy page schema read permission");
            return;
        }

        assertEquals("page.page.read", field.get(null));
        assertEquals("meta.page.read", MetaPermission.PAGE_READ);
    }
}
