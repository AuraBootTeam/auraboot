package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.plugin.dao.entity.PluginImportLog;
import com.auraboot.framework.plugin.dao.mapper.PluginImportLogMapper;
import com.auraboot.framework.plugin.dto.ConflictItem;
import com.auraboot.framework.plugin.dto.PluginImportRequest;
import com.auraboot.framework.plugin.dto.PluginImportResult;
import com.auraboot.framework.plugin.service.PluginConflictChecker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

/**
 * Unit tests for PluginTransactionalImportServiceImpl covering conflict short-circuit,
 * dry-run, success, and rollback paths.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginTransactionalImportServiceImpl Unit Tests")
class PluginTransactionalImportServiceImplTest {

    @Mock private PluginConflictChecker conflictChecker;
    @Mock private com.auraboot.framework.meta.mapper.PageSchemaMapper schemaMapper;
    @Mock private PermissionMapper permissionMapper;
    @Mock private MenuMapper menuMapper;
    @Mock private PluginImportLogMapper importLogMapper;

    @InjectMocks private PluginTransactionalImportServiceImpl service;

    @BeforeEach
    void setup() {
        MetaContext.setContext(100L, 1L, "U-1", "tester");
    }

    @AfterEach
    void teardown() {
        MetaContext.clear();
    }

    private PluginImportRequest baseRequest() {
        PluginImportRequest req = new PluginImportRequest();
        req.setPluginCode("test-plugin");
        req.setPluginVersion("1.0.0");
        return req;
    }

    @Test
    @DisplayName("dry-run returns OK when no conflicts and never persists")
    void dryRunNoConflictsReturnsOk() {
        when(conflictChecker.checkConflicts(any())).thenReturn(List.of());

        PluginImportResult result = service.importPlugin(baseRequest(), true);

        assertThat(result.getStatus()).isEqualTo("OK");
        verify(importLogMapper, never()).insert(any(PluginImportLog.class));
        verify(permissionMapper, never()).insert(any(Permission.class));
    }

    @Test
    @DisplayName("dry-run reports conflicts without writing import log")
    void dryRunReportsConflictsWithoutLog() {
        ConflictItem item = new ConflictItem();
        item.setType("permission");
        item.setCode("dup");
        when(conflictChecker.checkConflicts(any())).thenReturn(List.of(item));

        PluginImportResult result = service.importPlugin(baseRequest(), true);

        assertThat(result.getStatus()).isEqualTo("conflict");
        assertThat(result.getConflicts()).hasSize(1);
        verify(importLogMapper, never()).insert(any(PluginImportLog.class));
    }

    @Test
    @DisplayName("non-dry-run writes failure log when conflicts present")
    void nonDryRunWithConflictsLogsFailure() {
        ConflictItem item = new ConflictItem();
        item.setType("menu");
        item.setCode("dup-menu");
        when(conflictChecker.checkConflicts(any())).thenReturn(List.of(item));

        PluginImportResult result = service.importPlugin(baseRequest(), false);

        assertThat(result.getStatus()).isEqualTo("conflict");
        verify(importLogMapper).insert(any(PluginImportLog.class));
    }

    @Test
    @DisplayName("happy path imports permissions/menus and logs success")
    void happyPathSuccess() {
        PluginImportRequest req = baseRequest();
        req.setPermissions(List.of(Map.of("code", "perm.a", "name", "Perm A")));
        req.setMenus(List.of(Map.of("name", "Menu1", "path", "/m1", "type", 1, "orderNo", 0)));

        when(conflictChecker.checkConflicts(any())).thenReturn(List.of());
        // Permission/Menu mappers' insert returns int; populate ID via answer.
        doAnswer(inv -> {
            Permission p = inv.getArgument(0);
            p.setId(11L);
            return 1;
        }).when(permissionMapper).insert(any(Permission.class));
        doAnswer(inv -> {
            Menu m = inv.getArgument(0);
            m.setId(22L);
            return 1;
        }).when(menuMapper).insert(any(Menu.class));
        doAnswer(inv -> {
            PluginImportLog log = inv.getArgument(0);
            log.setId(99L);
            return 1;
        }).when(importLogMapper).insert(any(PluginImportLog.class));

        PluginImportResult result = service.importPlugin(req, false);

        assertThat(result.getStatus()).isEqualToIgnoringCase("success");
        assertThat(result.getPermissionsImported()).isEqualTo(1);
        assertThat(result.getMenusImported()).isEqualTo(1);
        verify(permissionMapper).insert(any(Permission.class));
        verify(menuMapper).insert(any(Menu.class));
    }

    @Test
    @DisplayName("rollback path triggers when permission insert fails")
    void rollbackOnFailure() {
        PluginImportRequest req = baseRequest();
        req.setPermissions(List.of(Map.of("code", "p1")));

        when(conflictChecker.checkConflicts(any())).thenReturn(List.of());
        when(permissionMapper.insert(any(Permission.class)))
                .thenThrow(new RuntimeException("db fail"));

        assertThatThrownBy(() -> service.importPlugin(req, false))
                .isInstanceOf(RootUnCheckedException.class)
                .hasMessageContaining("rolled back");

        // saveImportLog should be called for both initial start (success path attempt
        // doesn't write start log; only final rolled_back log is written).
        verify(importLogMapper).insert(any(PluginImportLog.class));
    }
}
