package com.auraboot.framework.integration.saas;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.saas.config.entity.SystemConfigEntity;
import com.auraboot.framework.saas.config.mapper.SystemConfigMapper;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.auraboot.framework.saas.config.service.impl.SystemConfigServiceImpl;
import com.auraboot.framework.saas.constant.SystemConfigKeys;
import com.auraboot.framework.saas.constant.SystemMode;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SystemModeServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SystemModeService systemModeService;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private SystemConfigMapper systemConfigMapper;

    /** Bootstrap-related config keys that must be absent for "default" tests. */
    private static final List<String> BOOTSTRAP_CONFIG_KEYS = List.of(
        SystemConfigKeys.SYSTEM_INITIALIZED,
        SystemConfigKeys.SYSTEM_DEFAULT_TENANT_ID,
        SystemConfigKeys.SYSTEM_SETUP_AT,
        SystemConfigKeys.SYSTEM_MODE,
        SystemConfigKeys.SYSTEM_PLATFORM_NAME,
        SystemConfigKeys.SYSTEM_ALLOW_SELF_REGISTRATION
    );

    @BeforeEach
    void clearCacheAndBootstrapState() {
        // Delete bootstrap config rows so tests see clean "pre-bootstrap" state
        // (rolled back by @Transactional after each test)
        for (String key : BOOTSTRAP_CONFIG_KEYS) {
            systemConfigMapper.delete(
                new QueryWrapper<SystemConfigEntity>().eq("config_key", key)
            );
        }
        // Clear in-memory cache to force re-read from DB
        if (systemConfigService instanceof SystemConfigServiceImpl impl) {
            ReflectionTestUtils.setField(impl, "cacheExpiry", 0L);
            @SuppressWarnings("unchecked")
            Map<String, String> cache = (Map<String, String>) ReflectionTestUtils.getField(impl, "cache");
            if (cache != null) cache.clear();
        }
    }

    @Test
    @Order(1)
    void getMode_shouldDefaultToSingle() {
        assertThat(systemModeService.getMode()).isEqualTo(SystemMode.SINGLE);
    }

    @Test
    @Order(2)
    void isSingleTenant_shouldBeTrueByDefault() {
        assertThat(systemModeService.isSingleTenant()).isTrue();
    }

    @Test
    @Order(3)
    void isMultiTenant_shouldBeFalseByDefault() {
        assertThat(systemModeService.isMultiTenant()).isFalse();
    }

    @Test
    @Order(4)
    void getMode_shouldReflectConfigChange() {
        systemConfigService.initialize(SystemConfigKeys.SYSTEM_MODE,
            SystemMode.MULTI.getCode(), "system", "string", "System mode", false);
        assertThat(systemModeService.getMode()).isEqualTo(SystemMode.MULTI);
        assertThat(systemModeService.isMultiTenant()).isTrue();
        assertThat(systemModeService.isSingleTenant()).isFalse();
    }

    @Test
    @Order(5)
    void isSetupComplete_shouldBeFalseByDefault() {
        assertThat(systemModeService.isSetupComplete()).isFalse();
    }

    @Test
    @Order(6)
    void getDefaultTenantId_shouldReturnZeroByDefault() {
        assertThat(systemModeService.getDefaultTenantId()).isEqualTo(0L);
    }

    @Test
    @Order(7)
    void isRegistrationAllowed_shouldBeFalseInSingleMode() {
        systemConfigService.initialize(SystemConfigKeys.SYSTEM_MODE,
            SystemMode.SINGLE.getCode(), "system", "string", "System mode", false);
        assertThat(systemModeService.isRegistrationAllowed()).isFalse();
    }

    @Test
    @Order(8)
    void isRegistrationAllowed_shouldBeTrueInMultiMode() {
        systemConfigService.initialize(SystemConfigKeys.SYSTEM_MODE,
            SystemMode.MULTI.getCode(), "system", "string", "System mode", false);
        assertThat(systemModeService.isRegistrationAllowed()).isTrue();
    }
}
