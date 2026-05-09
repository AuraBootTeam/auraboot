package com.auraboot.framework.i18n.sync;

import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.i18n.compiler.I18nCompiler;
import com.auraboot.framework.i18n.service.I18nResourceService;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class I18nSyncServiceTest {

    @Mock private I18nResourceService i18nResourceService;
    @Mock private I18nService i18nService;
    @Mock private I18nCompiler i18nCompiler;
    @Mock private MetaModelService metaModelService;
    @Mock private MetaFieldService metaFieldService;
    @InjectMocks private I18nSyncService service;

    private MetaModelDTO model(Long id, String code, String name) {
        return MetaModelDTO.builder().id(id).code(code).displayName(name).build();
    }

    private MetaFieldDTO field(Long id, String code, String displayName, String description, Map<String, Object> extraExt) {
        Map<String, Object> ext = new HashMap<>();
        if (displayName != null) ext.put("displayName", displayName);
        if (description != null) ext.put("description", description);
        if (extraExt != null) ext.putAll(extraExt);
        MetaFieldDTO f = new MetaFieldDTO();
        f.setId(id);
        f.setCode(code);
        f.setExtension(ext.isEmpty() ? null : ext);
        return f;
    }

    @Test
    void syncFromModel_nullModelIsNoop() {
        service.syncFromModel(null);
        verifyNoInteractions(i18nResourceService);
    }

    @Test
    void syncFromModel_blankDisplayNameSkipsResource() {
        MetaModelDTO m = model(1L, "User", "");
        service.syncFromModel(m);
        verify(i18nResourceService, never()).syncFromModel(any(), any(), any());
    }

    @Test
    void syncFromModel_callsResourceService() {
        MetaModelDTO m = model(1L, "User", "User Display");
        service.syncFromModel(m);
        verify(i18nResourceService).syncFromModel(1L, "User", "User Display");
    }

    @Test
    void syncFromField_nullFieldIsNoop() {
        service.syncFromField(null, "M");
        verifyNoInteractions(i18nResourceService);
    }

    @Test
    void syncFromField_blankModelCodeIsNoop() {
        service.syncFromField(new MetaFieldDTO(), "");
        verifyNoInteractions(i18nResourceService);
    }

    @Test
    void syncFromField_extractsPlaceholderFromExtensionMap() {
        Map<String, Object> ph = new HashMap<>();
        ph.put("placeholder", "Enter name");
        MetaFieldDTO f = field(2L, "name", "Name", "desc", ph);

        service.syncFromField(f, "User");
        verify(i18nResourceService).syncFromField(2L, "User", "name", "Name", "Enter name", "desc");
    }

    @Test
    void syncFromField_handlesNullExtensionAndNonStringPlaceholder() {
        // Null extension map: getDisplayName falls back to code "age", description is null
        MetaFieldDTO f = new MetaFieldDTO();
        f.setId(3L);
        f.setCode("age");
        service.syncFromField(f, "User");
        verify(i18nResourceService).syncFromField(3L, "User", "age", "age", null, null);

        // non-string placeholder; extension present with displayName
        Map<String, Object> ext = new HashMap<>();
        ext.put("displayName", "Age");
        ext.put("placeholder", 123);
        f.setExtension(ext);
        service.syncFromField(f, "User");
        verify(i18nResourceService).syncFromField(eq(3L), eq("User"), eq("age"), eq("Age"), isNull(), isNull());
    }

    @Test
    void syncFromFieldDefinition_nullOrBlankIsNoop() {
        service.syncFromFieldDefinition(null, "M");
        service.syncFromFieldDefinition(new FieldDefinition(), null);
        verifyNoInteractions(i18nResourceService);
    }

    @Test
    void syncFromFieldDefinition_extractsPlaceholderFromExtraProps() {
        FieldDefinition fd = new FieldDefinition();
        fd.setCode("c");
        fd.setDisplayName("C");
        fd.setDescription("d");
        Map<String, Object> extra = new HashMap<>();
        extra.put("placeholder", "ph");
        fd.setExtraProps(extra);

        service.syncFromFieldDefinition(fd, "M");
        verify(i18nResourceService).syncFromField(null, "M", "c", "C", "ph", "d");
    }

    @Test
    void syncFromFieldDefinition_nonStringPlaceholderIsNull() {
        FieldDefinition fd = new FieldDefinition();
        fd.setCode("c");
        fd.setDisplayName("C");
        Map<String, Object> extra = new HashMap<>();
        extra.put("placeholder", 999);
        fd.setExtraProps(extra);

        service.syncFromFieldDefinition(fd, "M");
        verify(i18nResourceService).syncFromField(isNull(), eq("M"), eq("c"), eq("C"), isNull(), isNull());
    }

    @Test
    void syncAll_iteratesModelsAndFields() {
        MetaModelDTO m1 = model(1L, "User", "User");
        PageResult<MetaModelDTO> page1 = new PageResult<>();
        page1.setRecords(List.of(m1));
        page1.setTotal(1L);
        when(metaModelService.searchModels(eq(1), eq(100), any(), any(), any(), any(), any(), any(), any(), any(), eq(true)))
                .thenReturn(page1);

        FieldDefinition fd = new FieldDefinition();
        fd.setCode("name");
        fd.setDisplayName("Name");
        when(metaModelService.getModelFields("User")).thenReturn(List.of(fd));

        I18nSyncService.SyncResult r = service.syncAll();

        assertTrue(r.isSuccess());
        assertEquals(1, r.getModelsProcessed());
        assertEquals(1, r.getFieldsProcessed());
        verify(i18nService).clearCache(null);
    }

    @Test
    void syncAll_handlesEmptyPage() {
        PageResult<MetaModelDTO> page = new PageResult<>();
        page.setRecords(Collections.emptyList());
        page.setTotal(0L);
        when(metaModelService.searchModels(anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(page);

        I18nSyncService.SyncResult r = service.syncAll();
        assertTrue(r.isSuccess());
        assertEquals(0, r.getModelsProcessed());
    }

    @Test
    void syncAll_capturesPerFieldFailure() {
        MetaModelDTO m1 = model(1L, "User", "User");
        PageResult<MetaModelDTO> page = new PageResult<>();
        page.setRecords(List.of(m1));
        page.setTotal(1L);
        when(metaModelService.searchModels(anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(page);

        FieldDefinition fd = new FieldDefinition();
        fd.setCode("c");
        fd.setDisplayName("C");
        when(metaModelService.getModelFields("User")).thenReturn(List.of(fd));
        doThrow(new RuntimeException("boom")).when(i18nResourceService)
                .syncFromField(isNull(), eq("User"), eq("c"), eq("C"), isNull(), isNull());

        I18nSyncService.SyncResult r = service.syncAll();
        assertEquals(1, r.getFieldsFailed());
    }

    @Test
    void syncAll_capturesTopLevelFailure() {
        when(metaModelService.searchModels(anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("db down"));

        I18nSyncService.SyncResult r = service.syncAll();
        assertFalse(r.isSuccess());
        assertEquals("db down", r.getError());
    }

    @Test
    void syncAndCompileAsync_compilesOnSuccess() {
        PageResult<MetaModelDTO> page = new PageResult<>();
        page.setRecords(Collections.emptyList());
        page.setTotal(0L);
        when(metaModelService.searchModels(anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(page);
        I18nCompiler.CompileResult cr = new I18nCompiler.CompileResult();
        cr.setTotalKeys(5);
        when(i18nCompiler.compileAll()).thenReturn(cr);

        service.syncAndCompileAsync();
        verify(i18nCompiler).compileAll();
    }

    @Test
    void syncAndCompileAsync_skipsCompileOnFailure() {
        when(metaModelService.searchModels(anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("x"));
        service.syncAndCompileAsync();
        verify(i18nCompiler, never()).compileAll();
    }

    @Test
    void syncResultDataAccessors() {
        I18nSyncService.SyncResult r = new I18nSyncService.SyncResult();
        r.setSuccess(true);
        r.setModelsProcessed(3);
        r.setModelsFailed(1);
        r.setFieldsProcessed(10);
        r.setFieldsFailed(2);
        r.setError("err");
        assertTrue(r.isSuccess());
        assertEquals(3, r.getModelsProcessed());
        assertEquals(1, r.getModelsFailed());
        assertEquals(10, r.getFieldsProcessed());
        assertEquals(2, r.getFieldsFailed());
        assertEquals("err", r.getError());
    }
}
