package com.auraboot.framework.menu.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MenuServiceImpl
 */
class MenuServiceImplTest {

    @Test
    @DisplayName("convertPathToResourceCode should convert valid paths correctly")
    void testConvertPathToResourceCode_validPaths() throws Exception {
        MenuServiceImpl service = new MenuServiceImpl();
        Method method = MenuServiceImpl.class.getDeclaredMethod("convertPathToResourceCode", String.class);
        method.setAccessible(true);

        // Test basic path conversion
        assertEquals("meta_models", method.invoke(service, "/meta/models"));
        assertEquals("meta", method.invoke(service, "/meta"));
        assertEquals("system", method.invoke(service, "/system"));

        // Test hyphen to underscore
        assertEquals("data_permissions", method.invoke(service, "/data-permissions"));
        assertEquals("api_connectors", method.invoke(service, "/api-connectors"));

        // Test multiple segments
        assertEquals("enterprise_members", method.invoke(service, "/enterprise/members"));
        assertEquals("meta_models_list", method.invoke(service, "/meta/models/list"));

        // Test without leading slash
        assertEquals("meta_models", method.invoke(service, "meta/models"));
    }

    @Test
    @DisplayName("convertPathToResourceCode should return null for invalid paths")
    void testConvertPathToResourceCode_invalidPaths() throws Exception {
        MenuServiceImpl service = new MenuServiceImpl();
        Method method = MenuServiceImpl.class.getDeclaredMethod("convertPathToResourceCode", String.class);
        method.setAccessible(true);

        // Null or empty
        assertNull(method.invoke(service, (String) null));
        assertNull(method.invoke(service, ""));

        // Starts with number (invalid after conversion)
        assertNull(method.invoke(service, "/123/test"));

        // Only special characters
        assertNull(method.invoke(service, "/---"));
    }

    @Test
    @DisplayName("convertPathToResourceCode should handle edge cases")
    void testConvertPathToResourceCode_edgeCases() throws Exception {
        MenuServiceImpl service = new MenuServiceImpl();
        Method method = MenuServiceImpl.class.getDeclaredMethod("convertPathToResourceCode", String.class);
        method.setAccessible(true);

        // Multiple slashes
        assertEquals("a_b_c", method.invoke(service, "/a//b///c"));

        // Mixed case
        assertEquals("meta_models", method.invoke(service, "/META/MODELS"));

        // Trailing slash
        assertEquals("meta_models", method.invoke(service, "/meta/models/"));

        // Special characters are removed (not replaced with underscore)
        assertEquals("testpath", method.invoke(service, "/test@path!"));
    }
}
