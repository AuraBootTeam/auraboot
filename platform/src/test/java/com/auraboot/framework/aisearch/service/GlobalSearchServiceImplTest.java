package com.auraboot.framework.aisearch.service;

import com.auraboot.framework.aisearch.dto.GlobalSearchResult;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.permission.service.UserPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GlobalSearchServiceImpl.
 * <p>
 * Verifies the permission boundary (fail-closed candidate convergence), the
 * grouped result shape, budget clamps, and degradation of a single unqueryable
 * model — in isolation from the database.
 */
@ExtendWith(MockitoExtension.class)
class GlobalSearchServiceImplTest {

    @Mock
    private MetaModelMapper metaModelMapper;

    @Mock
    private DynamicDataService dynamicDataService;

    @Mock
    private UserPermissionService userPermissionService;

    private GlobalSearchServiceImpl service;

    private static final Long USER_ID = 42L;

    private Model model(String code, String type) {
        Model m = new Model();
        m.setCode(code);
        ExtensionBean extension = new ExtensionBean();
        extension.setDynamicProperty("displayName", code.toUpperCase());
        extension.setDynamicProperty("modelType", type);
        m.setExtension(extension);
        return m;
    }

    private PaginationResult<Map<String, Object>> page(List<Map<String, Object>> records, long total) {
        PaginationResult<Map<String, Object>> result = new PaginationResult<>();
        result.setRecords(records);
        result.setTotal(total);
        return result;
    }

    @BeforeEach
    void setUp() {
        service = new GlobalSearchServiceImpl(metaModelMapper, dynamicDataService, userPermissionService);
    }

    private void stubReadable(String code, boolean readable) {
        when(userPermissionService.hasPermission(eq(USER_ID), eq("model." + code + ".read")))
                .thenReturn(readable);
    }

    @Test
    void candidatesDropViewAndMetaModelsAndModelsWithoutReadPermission() {
        when(metaModelMapper.findCurrentByTenant()).thenReturn(List.of(
                model("crm_account_common", null),
                model("some_view", "view"),
                model("meta_thing", "meta"),
                model("sl_sales_order_common", null)));
        stubReadable("crm_account_common", true);
        stubReadable("sl_sales_order_common", false);

        List<Model> candidates = service.readableCandidateModels(USER_ID);

        assertEquals(List.of("crm_account_common"),
                candidates.stream().map(Model::getCode).toList());
    }

    @Test
    void candidatesRequireAuthenticatedUser() {
        assertThrows(IllegalStateException.class, () -> service.readableCandidateModels(null));
    }

    @Test
    void searchGroupsHitsPerModelAndKeepsRawRows() {
        when(metaModelMapper.findCurrentByTenant()).thenReturn(List.of(
                model("crm_account_common", null),
                model("crm_contact_common", null)));
        stubReadable("crm_account_common", true);
        stubReadable("crm_contact_common", true);

        Map<String, Object> row = Map.of("pid", "01MTEST", "crm_acc_name", "Acme");
        when(dynamicDataService.list(eq("crm_account_common"), any()))
                .thenReturn(page(List.of(row), 7L));
        when(dynamicDataService.list(eq("crm_contact_common"), any()))
                .thenReturn(page(List.of(), 0L));

        GlobalSearchResult result = service.search(USER_ID, "acme", null, null);

        assertEquals(1, result.getGroups().size());
        GlobalSearchResult.Group group = result.getGroups().get(0);
        assertEquals("crm_account_common", group.getModelCode());
        assertEquals("CRM_ACCOUNT_COMMON", group.getModelLabel());
        assertEquals(7L, group.getTotal());
        assertEquals("01MTEST", group.getRecords().get(0).get("pid"));
        assertFalse(result.isTruncated());
    }

    @Test
    void searchDropsModelsWithoutReadPermission() {
        when(metaModelMapper.findCurrentByTenant()).thenReturn(List.of(
                model("crm_account_common", null),
                model("hr_secret_model", null)));
        stubReadable("crm_account_common", true);
        stubReadable("hr_secret_model", false);

        when(dynamicDataService.list(eq("crm_account_common"), any()))
                .thenReturn(page(List.of(Map.of("pid", "01MP", "name", "Acme")), 1L));

        GlobalSearchResult result = service.search(USER_ID, "acme", null, null);

        assertTrue(result.getGroups().stream()
                .noneMatch(g -> g.getModelCode().equals("hr_secret_model")));
        verify(dynamicDataService, never()).list(eq("hr_secret_model"), any());
    }

    @Test
    void searchMarksTruncatedWhenModelBudgetExhausted() {
        when(metaModelMapper.findCurrentByTenant()).thenReturn(List.of(
                model("m_one", null), model("m_two", null), model("m_three", null)));
        stubReadable("m_one", true);
        stubReadable("m_two", true);
        stubReadable("m_three", true);
        when(dynamicDataService.list(anyString(), any()))
                .thenReturn(page(List.of(Map.of("pid", "01MP")), 1L));

        GlobalSearchResult result = service.search(USER_ID, "acme", null, 2);

        assertEquals(2, result.getGroups().size());
        assertTrue(result.isTruncated());
    }

    @Test
    void searchSurvivesSingleUnqueryableModel() {
        when(metaModelMapper.findCurrentByTenant()).thenReturn(List.of(
                model("broken_model", null),
                model("crm_account_common", null)));
        stubReadable("broken_model", true);
        stubReadable("crm_account_common", true);
        when(dynamicDataService.list(eq("broken_model"), any()))
                .thenThrow(new IllegalStateException("no backing table"));
        when(dynamicDataService.list(eq("crm_account_common"), any()))
                .thenReturn(page(List.of(Map.of("pid", "01MP")), 1L));

        GlobalSearchResult result = service.search(USER_ID, "acme", null, null);

        assertEquals(List.of("crm_account_common"),
                result.getGroups().stream().map(GlobalSearchResult.Group::getModelCode).toList());
    }

    @Test
    void searchRejectsBlankKeyword() {
        assertThrows(IllegalArgumentException.class, () -> service.search(USER_ID, "   ", null, null));
        assertThrows(IllegalArgumentException.class, () -> service.search(USER_ID, null, null, null));
    }
}
