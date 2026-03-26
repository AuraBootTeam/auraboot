package com.auraboot.framework.integration.saas;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.saas.config.entity.SystemConfigEntity;
import com.auraboot.framework.saas.config.mapper.SystemConfigMapper;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.config.service.impl.SystemConfigServiceImpl;
import com.auraboot.framework.saas.constant.SystemConfigKeys;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SystemConfigServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private SystemConfigMapper systemConfigMapper;

    private final String testKey = "test.config." + System.currentTimeMillis();

    @BeforeEach
    void clearCache() {
        if (systemConfigService instanceof SystemConfigServiceImpl impl) {
            ReflectionTestUtils.setField(impl, "cacheExpiry", 0L);
            @SuppressWarnings("unchecked")
            Map<String, String> cache = (Map<String, String>) ReflectionTestUtils.getField(impl, "cache");
            if (cache != null) cache.clear();
        }
    }

    @Test
    @Order(1)
    void initialize_shouldCreateNewConfig() {
        systemConfigService.initialize(testKey, "hello", "system", "string",
            "Test config", false);
        Optional<String> value = systemConfigService.get(testKey);
        assertThat(value).isPresent().hasValue("hello");
    }

    @Test
    @Order(2)
    void initialize_shouldBeIdempotent() {
        String key = "test.idempotent." + System.currentTimeMillis();
        systemConfigService.initialize(key, "v1", "system", "string", "Test", false);
        systemConfigService.initialize(key, "v2", "system", "string", "Test", false);
        assertThat(systemConfigService.get(key)).hasValue("v2");
    }

    @Test
    @Order(3)
    void set_shouldUpdateMutableConfig() {
        String key = "test.mutable." + System.currentTimeMillis();
        systemConfigService.initialize(key, "old", "system", "string", "Test", false);
        systemConfigService.set(key, "new");
        assertThat(systemConfigService.get(key)).hasValue("new");
    }

    @Test
    @Order(4)
    void set_shouldRejectReadonlyConfig() {
        String key = "test.readonly." + System.currentTimeMillis();
        systemConfigService.initialize(key, "locked", "system", "string",
            "Readonly test", true);
        assertThatThrownBy(() -> systemConfigService.set(key, "changed"))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @Order(5)
    void set_shouldRejectNonexistentKey() {
        assertThatThrownBy(() -> systemConfigService.set("nonexistent.key." + System.currentTimeMillis(), "value"))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @Order(6)
    void getBoolean_shouldParseCorrectly() {
        String key = "test.bool." + System.currentTimeMillis();
        systemConfigService.initialize(key, "true", "system", "boolean", "Test", false);
        assertThat(systemConfigService.getBoolean(key, false)).isTrue();
    }

    @Test
    @Order(7)
    void getLong_shouldParseCorrectly() {
        String key = "test.long." + System.currentTimeMillis();
        systemConfigService.initialize(key, "42", "system", "integer", "Test", false);
        assertThat(systemConfigService.getLong(key, 0L)).isEqualTo(42L);
    }

    @Test
    @Order(8)
    void get_shouldReturnEmptyForMissingKey() {
        assertThat(systemConfigService.get("missing.key." + System.currentTimeMillis()))
            .isEmpty();
    }

    @Test
    @Order(9)
    void getOrDefault_shouldReturnDefaultForMissingKey() {
        assertThat(systemConfigService.getOrDefault(
            "missing.key." + System.currentTimeMillis(), "fallback"))
            .isEqualTo("fallback");
    }

    @Test
    @Order(10)
    void isInitialized_shouldReturnFalseByDefault() {
        // Delete the system.initialized row if it exists from real bootstrap
        // (rolled back by @Transactional after the test)
        systemConfigMapper.delete(
            new QueryWrapper<SystemConfigEntity>()
                .eq("config_key", SystemConfigKeys.SYSTEM_INITIALIZED)
        );
        // Re-clear cache after DB delete so next read goes to DB
        clearCache();
        assertThat(systemConfigService.isInitialized()).isFalse();
    }
}
