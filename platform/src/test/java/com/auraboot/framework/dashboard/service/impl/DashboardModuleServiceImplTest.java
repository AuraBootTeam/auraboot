package com.auraboot.framework.dashboard.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.dashboard.dto.DashboardModuleCreateRequest;
import com.auraboot.framework.dashboard.dto.DashboardModuleDTO;
import com.auraboot.framework.dashboard.dto.DashboardModuleMoveRequest;
import com.auraboot.framework.dashboard.entity.DashboardModule;
import com.auraboot.framework.dashboard.mapper.DashboardMapper;
import com.auraboot.framework.dashboard.mapper.DashboardModuleMapper;
import com.auraboot.framework.exception.ValidationException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardModuleServiceImpl")
class DashboardModuleServiceImplTest {

    @Mock private DashboardModuleMapper dashboardModuleMapper;
    @Mock private DashboardMapper dashboardMapper;

    private DashboardModuleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DashboardModuleServiceImpl(dashboardModuleMapper, dashboardMapper);
        MetaContext.setContext(10L, 1L, "u-1", "user");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private DashboardModule folder(Long id, String pid, String name, Long parentId) {
        DashboardModule m = new DashboardModule();
        m.setId(id);
        m.setPid(pid);
        m.setTenantId(10L);
        m.setName(name);
        m.setParentId(parentId);
        m.setSortOrder(0);
        m.setDeletedFlag(false);
        return m;
    }

    private DashboardModuleCreateRequest createRequest(String name, String parentPid) {
        DashboardModuleCreateRequest request = new DashboardModuleCreateRequest();
        request.setName(name);
        request.setParentPid(parentPid);
        return request;
    }

    @Test
    @DisplayName("create nests under the requested parent folder")
    void createNestsUnderParent() {
        DashboardModule parent = folder(1L, "mod-parent", "销售目录", null);
        when(dashboardModuleMapper.findByPid("mod-parent")).thenReturn(parent);
        when(dashboardModuleMapper.countChildren(10L, 1L)).thenReturn(0L);
        when(dashboardModuleMapper.selectById(1L)).thenReturn(parent);

        DashboardModuleDTO created = service.create(createRequest("华东", "mod-parent"));

        ArgumentCaptor<DashboardModule> captor = ArgumentCaptor.forClass(DashboardModule.class);
        verify(dashboardModuleMapper).insertModule(captor.capture());
        assertEquals("华东", captor.getValue().getName());
        assertEquals(1L, captor.getValue().getParentId());
        assertEquals("华东", created.getName());
        assertEquals("mod-parent", created.getParentPid());
    }

    @Test
    @DisplayName("create with blank parent creates a root folder")
    void createRootWhenParentBlank() {
        when(dashboardModuleMapper.countChildren(10L, null)).thenReturn(0L);

        DashboardModuleDTO created = service.create(createRequest("根目录", null));

        ArgumentCaptor<DashboardModule> captor = ArgumentCaptor.forClass(DashboardModule.class);
        verify(dashboardModuleMapper).insertModule(captor.capture());
        assertNull(captor.getValue().getParentId());
        assertNull(created.getParentPid());
    }

    @Test
    @DisplayName("create rejects an unknown parent folder")
    void createRejectsUnknownParent() {
        when(dashboardModuleMapper.findByPid("missing")).thenReturn(null);

        assertThrows(ValidationException.class,
                () -> service.create(createRequest("华东", "missing")));
        verify(dashboardModuleMapper, never()).insertModule(any(DashboardModule.class));
    }

    @Test
    @DisplayName("delete refuses a folder that still has dashboards")
    void deleteRefusesFolderWithDashboards() {
        DashboardModule folder = folder(1L, "mod-1", "销售目录", null);
        when(dashboardModuleMapper.findByPid("mod-1")).thenReturn(folder);
        when(dashboardModuleMapper.countChildren(10L, 1L)).thenReturn(0L);
        when(dashboardMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(2L);

        assertThrows(ValidationException.class, () -> service.delete("mod-1"));
        verify(dashboardModuleMapper, never()).softDelete(eq("mod-1"), any(), any());
    }

    @Test
    @DisplayName("delete refuses a folder that still has child folders")
    void deleteRefusesFolderWithChildren() {
        DashboardModule folder = folder(1L, "mod-1", "销售目录", null);
        when(dashboardModuleMapper.findByPid("mod-1")).thenReturn(folder);
        when(dashboardModuleMapper.countChildren(10L, 1L)).thenReturn(1L);

        assertThrows(ValidationException.class, () -> service.delete("mod-1"));
        verify(dashboardModuleMapper, never()).softDelete(eq("mod-1"), any(), any());
    }

    @Test
    @DisplayName("delete removes an empty folder")
    void deleteRemovesEmptyFolder() {
        DashboardModule folder = folder(1L, "mod-1", "销售目录", null);
        when(dashboardModuleMapper.findByPid("mod-1")).thenReturn(folder);
        when(dashboardModuleMapper.countChildren(10L, 1L)).thenReturn(0L);
        when(dashboardMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        service.delete("mod-1");

        verify(dashboardModuleMapper).softDelete(eq("mod-1"), any(), any());
    }

    @Test
    @DisplayName("tree renders nested children with dashboard counts")
    void treeRendersNesting() {
        DashboardModule root = folder(1L, "mod-root", "销售目录", null);
        DashboardModule child = folder(2L, "mod-child", "华东", 1L);
        when(dashboardModuleMapper.findAllByTenant(10L)).thenReturn(List.of(root, child));
        when(dashboardMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("module_id", 1L, "cnt", 3L)));

        List<DashboardModuleDTO> tree = service.tree();

        assertEquals(1, tree.size());
        assertEquals("mod-root", tree.get(0).getPid());
        assertEquals(3L, tree.get(0).getDashboardCount());
        assertEquals(1, tree.get(0).getChildren().size());
        assertEquals("mod-child", tree.get(0).getChildren().get(0).getPid());
        assertEquals("mod-root", tree.get(0).getChildren().get(0).getParentPid());
    }

    @Test
    @DisplayName("move rejects a folder moved under its own descendant")
    void moveRejectsDescendantTarget() {
        // root(1) <- child(2); moving root under child would create a cycle.
        DashboardModule root = folder(1L, "mod-root", "销售目录", null);
        DashboardModule child = folder(2L, "mod-child", "华东", 1L);
        when(dashboardModuleMapper.findByPid("mod-root")).thenReturn(root);
        when(dashboardModuleMapper.findByPid("mod-child")).thenReturn(child);
        when(dashboardModuleMapper.selectById(2L)).thenReturn(child);

        DashboardModuleMoveRequest request = new DashboardModuleMoveRequest();
        request.setTargetParentPid("mod-child");

        assertThrows(ValidationException.class, () -> service.move("mod-root", request));
        verify(dashboardModuleMapper, never()).move(eq("mod-root"), any(), any(), any());
    }

    @Test
    @DisplayName("move accepts a valid new parent and persists it")
    void movePersistsNewParent() {
        DashboardModule source = folder(1L, "mod-a", "A", null);
        DashboardModule target = folder(2L, "mod-b", "B", null);
        when(dashboardModuleMapper.findByPid("mod-a")).thenReturn(source);
        when(dashboardModuleMapper.findByPid("mod-b")).thenReturn(target);
        when(dashboardMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        DashboardModuleMoveRequest request = new DashboardModuleMoveRequest();
        request.setTargetParentPid("mod-b");

        DashboardModuleDTO moved = service.move("mod-a", request);

        verify(dashboardModuleMapper).move(eq("mod-a"), eq(2L), any(), any());
        assertEquals("mod-a", moved.getPid());
    }

    @Test
    @DisplayName("move to blank target moves the folder to the root")
    void moveBlankTargetGoesToRoot() {
        DashboardModule source = folder(1L, "mod-a", "A", 2L);
        when(dashboardModuleMapper.findByPid("mod-a")).thenReturn(source);
        when(dashboardMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        DashboardModuleDTO moved = service.move("mod-a", new DashboardModuleMoveRequest());

        verify(dashboardModuleMapper).move(eq("mod-a"), isNull(), any(), any());
        assertEquals("mod-a", moved.getPid());
    }
}
