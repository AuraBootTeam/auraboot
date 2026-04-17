package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.saas.bootstrap.constant.BootstrapMissingPart;
import com.auraboot.framework.saas.bootstrap.mapper.BootstrapStatusMapper;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BootstrapStatusEvaluatorTest {

    @Mock BootstrapStatusMapper bootstrapStatusMapper;
    @Mock SystemConfigService systemConfigService;
    @InjectMocks BootstrapStatusEvaluator evaluator;

    @Test
    void empty_database_lists_all_missing_parts() {
        when(bootstrapStatusMapper.countPlatformAdminAssignments(anyString())).thenReturn(0L);
        when(bootstrapStatusMapper.countTenantById(anyLong())).thenReturn(0L);
        when(systemConfigService.isInitialized()).thenReturn(false);

        var result = evaluator.evaluate();

        assertThat(result.missingParts()).containsExactlyInAnyOrder(
                BootstrapMissingPart.ADMIN_USER,
                BootstrapMissingPart.DEFAULT_TENANT,
                BootstrapMissingPart.SYSTEM_CONFIG);
        assertThat(result.reason())
                .contains(BootstrapMissingPart.ADMIN_USER)
                .contains(BootstrapMissingPart.DEFAULT_TENANT)
                .contains(BootstrapMissingPart.SYSTEM_CONFIG);
    }

    @Test
    void only_admin_missing_lists_only_admin() {
        when(bootstrapStatusMapper.countPlatformAdminAssignments(anyString())).thenReturn(0L);
        when(bootstrapStatusMapper.countTenantById(anyLong())).thenReturn(1L);
        when(systemConfigService.isInitialized()).thenReturn(true);

        var result = evaluator.evaluate();

        assertThat(result.missingParts()).containsExactly(BootstrapMissingPart.ADMIN_USER);
        assertThat(result.reason()).contains(BootstrapMissingPart.ADMIN_USER);
    }

    @Test
    void fully_initialized_returns_empty_list_and_null_reason() {
        when(bootstrapStatusMapper.countPlatformAdminAssignments(anyString())).thenReturn(5L);
        when(bootstrapStatusMapper.countTenantById(anyLong())).thenReturn(1L);
        when(systemConfigService.isInitialized()).thenReturn(true);

        var result = evaluator.evaluate();

        assertThat(result.missingParts()).isEmpty();
        assertThat(result.reason()).isNull();
    }

    @Test
    void only_system_config_missing_returns_only_system_config() {
        when(bootstrapStatusMapper.countPlatformAdminAssignments(anyString())).thenReturn(2L);
        when(bootstrapStatusMapper.countTenantById(anyLong())).thenReturn(1L);
        when(systemConfigService.isInitialized()).thenReturn(false);

        var result = evaluator.evaluate();

        assertThat(result.missingParts()).containsExactly(BootstrapMissingPart.SYSTEM_CONFIG);
    }
}
