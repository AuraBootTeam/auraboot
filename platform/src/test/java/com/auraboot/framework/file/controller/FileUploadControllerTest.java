package com.auraboot.framework.file.controller;

import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.entity.FileRelationEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.meta.service.DataAccessAuthorizationHelper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FileUploadControllerTest {

    @Mock
    private FileService fileService;

    @Mock
    private StorageProvider storageProvider;

    @Mock
    private DynamicDataService dynamicDataService;

    @Mock
    private DataAccessAuthorizationHelper dataAccessAuthorizationHelper;

    @Mock
    private com.auraboot.framework.audit.service.AdminEventLogService adminEventLogService;

    private MockMvc mvc;
    private FileUploadController controller;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().doCallRealMethod().when(dynamicDataService)
                .withCanonicalRecordRead(any(),any(),any());
        controller = new FileUploadController();
        ReflectionTestUtils.setField(controller, "fileService", fileService);
        ReflectionTestUtils.setField(controller, "storageProvider", storageProvider);
        ReflectionTestUtils.setField(controller, "dynamicDataService", dynamicDataService);
        ReflectionTestUtils.setField(controller, "dataAccessAuthorizationHelper", dataAccessAuthorizationHelper);
        ReflectionTestUtils.setField(controller, "adminEventLogService", adminEventLogService);
        HandlerMethodArgumentResolver currentUserResolver = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(org.springframework.core.MethodParameter parameter) {
                return parameter.hasParameterAnnotation(
                        com.auraboot.framework.application.annotation.CurrentUserId.class);
            }

            @Override
            public Object resolveArgument(
                    org.springframework.core.MethodParameter parameter,
                    org.springframework.web.method.support.ModelAndViewContainer mavContainer,
                    org.springframework.web.context.request.NativeWebRequest webRequest,
                    org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
                return 42L;
            }
        };
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(currentUserResolver)
                .build();
    }

    @Test
    void getFile_absentFromTenantReturnsSafeNotFoundWithoutReadingStorage() throws Exception {
        mvc.perform(get("/api/file/missing-file").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404"))
                .andExpect(jsonPath("$.message").value("Resource not found"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.context").doesNotExist());
        verifyNoInteractions(storageProvider, dataAccessAuthorizationHelper, adminEventLogService);
    }

    @Test
    void getFile_ownerReceivesMetadataWithoutServerStoragePath() throws Exception {
        FileEntity file = storedFile("quote.pdf", "application/pdf", "/private/storage/quote.pdf");
        file.setFileSize(123L);
        when(fileService.getFileById("file-pid")).thenReturn(file);
        when(fileService.getFileDownloadUrl("file-pid")).thenReturn("/api/file/download/file-pid");
        when(fileService.getFileRelations("file-pid")).thenReturn(List.of());

        mvc.perform(get("/api/file/file-pid").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileId").value("file-pid"))
                .andExpect(jsonPath("$.data.originalName").value("quote.pdf"))
                .andExpect(jsonPath("$.data.mimeType").value("application/pdf"))
                .andExpect(jsonPath("$.data.fileSize").value(123))
                .andExpect(jsonPath("$.data.url").value("/api/file/download/file-pid"))
                .andExpect(jsonPath("$.data.localPath").doesNotExist());
        verifyNoInteractions(storageProvider, dataAccessAuthorizationHelper, adminEventLogService);
    }

    @Test
    void downloadFile_absentFromTenantDoesNotReadStorage() throws Exception {
        mvc.perform(get("/api/file/download/missing-file"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
        verifyNoInteractions(storageProvider, dataAccessAuthorizationHelper, adminEventLogService);
    }

    @Test
    void downloadFile_svgImage_returnsInlineSvgResponse() throws Exception {
        FileEntity file = storedFile("board.svg", "image/svg+xml", "/tmp/board.svg");
        when(fileService.getFileById("01KV22CQ7PKX3W50Y7MM575ACK")).thenReturn(file);
        when(storageProvider.download("/tmp/board.svg"))
                .thenReturn(new ByteArrayInputStream("<svg/>".getBytes(StandardCharsets.UTF_8)));

        mvc.perform(get("/api/file/download/01KV22CQ7PKX3W50Y7MM575ACK"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/svg+xml"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"board.svg\""))
                .andExpect(content().string("<svg/>"));
    }

    @Test
    void downloadFile_pdf_returnsInlineDispositionForBrowserPreview() throws Exception {
        FileEntity file = storedFile("quote.pdf", "application/pdf", "/tmp/quote.pdf");
        when(fileService.getFileById("file-pid")).thenReturn(file);
        when(storageProvider.download("/tmp/quote.pdf"))
                .thenReturn(new ByteArrayInputStream("pdf".getBytes(StandardCharsets.UTF_8)));

        mvc.perform(get("/api/file/download/file-pid"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"quote.pdf\""))
                .andExpect(content().string("pdf"));
    }

    @Test
    void downloadFile_chineseName_usesUtf8FilenameStar() throws Exception {
        FileEntity file = storedFile("原始-E1-V1.3-BOM-20240429.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "/tmp/source.xlsx");
        when(fileService.getFileById("file-pid")).thenReturn(file);
        when(storageProvider.download("/tmp/source.xlsx"))
                .thenReturn(new ByteArrayInputStream("xlsx".getBytes(StandardCharsets.UTF_8)));

        mvc.perform(get("/api/file/download/file-pid"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"E1-V1.3-BOM-20240429.xlsx\"; filename*=UTF-8''%E5%8E%9F%E5%A7%8B-E1-V1.3-BOM-20240429.xlsx"))
                .andExpect(content().string("xlsx"));
    }

    @Test
    void downloadFile_legacyMojibakeChineseName_recoversUtf8Filename() throws Exception {
        FileEntity file = storedFile("å\u008E\u009Få§\u008B-E1-V1.3-BOM-20240429.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "/tmp/source.xlsx");
        when(fileService.getFileById("file-pid")).thenReturn(file);
        when(storageProvider.download("/tmp/source.xlsx"))
                .thenReturn(new ByteArrayInputStream("xlsx".getBytes(StandardCharsets.UTF_8)));

        mvc.perform(get("/api/file/download/file-pid"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"E1-V1.3-BOM-20240429.xlsx\"; filename*=UTF-8''%E5%8E%9F%E5%A7%8B-E1-V1.3-BOM-20240429.xlsx"))
                .andExpect(content().string("xlsx"));
    }

    @Test
    void endpoints_declare_separate_leastPrivilegeFileCapabilities() throws Exception {
        assertPermission("uploadFile", MetaPermission.SYS_FILE_UPLOAD,
                org.springframework.web.multipart.MultipartFile.class, Long.class);
        assertPermission("getFile", MetaPermission.SYS_FILE_READ, String.class, Long.class);
        assertPermission("downloadFile", MetaPermission.SYS_FILE_READ, String.class, Long.class);
        assertPermission("deleteFile", MetaPermission.SYS_FILE_DELETE, String.class, Long.class);
        assertPermission("createFileRelation", MetaPermission.SYS_FILE_RELATION_MANAGE,
                com.auraboot.framework.file.dto.FileRelationRequestDTO.class, Long.class);
    }

    @Test
    void createRelation_authorizesTheTargetRecordBeforeWriting() {
        com.auraboot.framework.file.dto.FileRelationRequestDTO request =
                new com.auraboot.framework.file.dto.FileRelationRequestDTO();
        request.setEntityType("crm_customer_request_common");
        request.setEntityId("request-pid");
        request.setFieldName("source_files");
        request.setFileIds(new String[]{"file-pid"});
        when(dataAccessAuthorizationHelper.authorizeRecordId(
                eq("crm_customer_request_common"), eq("update"), eq("request-pid"), any()))
                .thenReturn(true);
        when(fileService.createFileRelation(request, 42L)).thenReturn(true);

        controller.createFileRelation(request, 42L);

        var ordered = inOrder(dataAccessAuthorizationHelper, fileService);
        ordered.verify(dataAccessAuthorizationHelper).authorizeRecordId(
                eq("crm_customer_request_common"), eq("update"), eq("request-pid"), any());
        ordered.verify(fileService).createFileRelation(request, 42L);
        verify(fileService).createFileRelation(request, 42L);
    }

    @Test
    void getFile_unrelatedUploaderCannotReadUnlinkedFileByPid() {
        FileEntity file = storedFile("private.txt", "text/plain", "/tmp/private.txt");
        file.setCreatedBy(7L);
        when(fileService.getFileById("file-pid")).thenReturn(file);
        when(fileService.getFileRelations("file-pid")).thenReturn(List.of());

        assertThatThrownBy(() -> controller.getFile("file-pid", 42L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void downloadFile_linkedRecordIsReauthorizedOnEveryRequest() {
        FileEntity file = storedFile("private.txt", "text/plain", "/tmp/private.txt");
        file.setCreatedBy(7L);
        FileRelationEntity relation = new FileRelationEntity()
                .setEntityType("crm_account_common")
                .setEntityId("account-pid")
                .setFieldName("attachments");
        when(fileService.getFileById("file-pid")).thenReturn(file);
        when(fileService.getFileRelations("file-pid")).thenReturn(List.of(relation));
        when(dataAccessAuthorizationHelper.authorizeRecordId(
                eq("crm_account_common"), eq("read"), eq("account-pid"), any()))
                .thenReturn(true);
        when(storageProvider.download("/tmp/private.txt"))
                .thenReturn(new ByteArrayInputStream("private".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<org.springframework.core.io.Resource> response =
                controller.downloadFile("file-pid", 42L);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(dataAccessAuthorizationHelper).authorizeRecordId(
                eq("crm_account_common"), eq("read"), eq("account-pid"), any());
        org.mockito.ArgumentCaptor<com.auraboot.framework.audit.entity.AdminEventLog> auditCaptor =
                org.mockito.ArgumentCaptor.forClass(com.auraboot.framework.audit.entity.AdminEventLog.class);
        verify(adminEventLogService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getActionType()).isEqualTo("file.download");
        assertThat(auditCaptor.getValue().getResourcePid()).isEqualTo("file-pid");
        assertThat(auditCaptor.getValue().getActorUserId()).isEqualTo(42L);
        assertThat(auditCaptor.getValue().getSuccess()).isTrue();
    }

    @Test
    void downloadFile_linkedRecordDenialStopsBeforeReadingBytes() {
        FileEntity file = storedFile("private.txt", "text/plain", "/tmp/private.txt");
        FileRelationEntity relation = new FileRelationEntity()
                .setEntityType("crm_account_common")
                .setEntityId("account-pid")
                .setFieldName("attachments");
        when(fileService.getFileById("file-pid")).thenReturn(file);
        when(fileService.getFileRelations("file-pid")).thenReturn(List.of(relation));
        when(dataAccessAuthorizationHelper.authorizeRecordId(
                eq("crm_account_common"), eq("read"), eq("account-pid"), any()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("revoked"));

        assertThatThrownBy(() -> controller.downloadFile("file-pid", 42L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessage("revoked");
        verify(storageProvider, org.mockito.Mockito.never()).download(any());
        verifyNoInteractions(adminEventLogService);
    }

    @Test void fileRelationAuthorizationUsesTheCanonicalReadWithoutDisplayExpansion() {
        var file = storedFile("original.json", "application/json", "/tmp/original");
        var relation = new FileRelationEntity().setEntityType("bundle").setEntityId("bundle-pid").setFieldName("original");
        when(fileService.getFileById("file-pid")).thenReturn(file);
        when(fileService.getFileRelations("file-pid")).thenReturn(List.of(relation));
        when(dynamicDataService.getByIdForAuthorization("bundle", "bundle-pid")).thenReturn(java.util.Map.of("pid", "bundle-pid"));
        when(dataAccessAuthorizationHelper.authorizeRecordId(eq("bundle"), eq("read"), eq("bundle-pid"), any()))
                .thenAnswer(call -> {
                    java.util.function.Function<String, java.util.Map<String, Object>> loader = call.getArgument(3);
                    return loader.apply("bundle-pid") != null;
                });
        assertThat(controller.getFile("file-pid", 42L).getStatusCode().value()).isEqualTo(200);
        verify(dynamicDataService).getByIdForAuthorization("bundle", "bundle-pid");
        org.mockito.Mockito.verify(dynamicDataService, org.mockito.Mockito.never()).getById(any(), any());
    }
    @Test void canonicalReadDefaultPreservesLoaderDenialAndFailureClassification() {
        var called=new java.util.concurrent.atomic.AtomicBoolean();
        var denied=new org.springframework.security.access.AccessDeniedException("revoked");
        when(dynamicDataService.getByIdForAuthorization("bundle","bundle-pid")).thenThrow(denied);
        assertThatThrownBy(()->dynamicDataService.withCanonicalRecordRead("bundle","bundle-pid",row -> called.set(true))).isSameAs(denied);
        var failure=new IllegalStateException("database unavailable");
        org.mockito.Mockito.doThrow(failure).when(dynamicDataService).getByIdForAuthorization("bundle","bundle-pid");
        assertThatThrownBy(()->dynamicDataService.withCanonicalRecordRead("bundle","bundle-pid",row -> called.set(true)))
                .isInstanceOf(com.auraboot.framework.meta.exception.MetaServiceException.class)
                .hasMessage("Failed to load record for authorization: bundle-pid").hasCause(failure);
        assertThat(called.get()).isFalse();
    }

    @Test void fileListSharesOnlyRequestMetadataAndReauthorizesEveryRelationOnTheNextRequest() {
        var file = storedFile("original.json", "application/json", "/tmp/original");
        var relation = new FileRelationEntity().setEntityType("bundle").setEntityId("bundle-pid").setFieldName("original");
        when(fileService.getFilesByUserId(42L)).thenReturn(List.of(file));
        when(fileService.getFileRelations("file-pid")).thenReturn(List.of(relation));
        when(dataAccessAuthorizationHelper.authorizeRecordId(eq("bundle"), eq("read"), eq("bundle-pid"), any()))
                .thenAnswer(call -> {
                    assertThat(com.auraboot.framework.meta.service.impl.DynamicDataQueryScope.isActive()).isTrue();
                    return true;
                }).thenThrow(new org.springframework.security.access.AccessDeniedException("revoked"));
        assertThat(controller.getUserFiles(42L).getData()).hasSize(1);
        assertThat(com.auraboot.framework.meta.service.impl.DynamicDataQueryScope.isActive()).isFalse();
        assertThat(controller.getUserFiles(42L).getData()).isEmpty();
        assertThat(com.auraboot.framework.meta.service.impl.DynamicDataQueryScope.isActive()).isFalse();
        org.mockito.Mockito.verify(dataAccessAuthorizationHelper, org.mockito.Mockito.times(2))
                .authorizeRecordId(eq("bundle"), eq("read"), eq("bundle-pid"), any());
    }

    @Test void fileListClosesItsMetadataScopeWhenAuthorizationHasAnUnexpectedFailure() {
        var file = storedFile("original.json", "application/json", "/tmp/original");
        var relation = new FileRelationEntity().setEntityType("bundle").setEntityId("bundle-pid").setFieldName("original");
        when(fileService.getFilesByUserId(42L)).thenReturn(List.of(file));
        when(fileService.getFileRelations("file-pid")).thenReturn(List.of(relation));
        when(dataAccessAuthorizationHelper.authorizeRecordId(eq("bundle"), eq("read"), eq("bundle-pid"), any()))
                .thenAnswer(call -> {
                    assertThat(com.auraboot.framework.meta.service.impl.DynamicDataQueryScope.isActive()).isTrue();
                    throw new IllegalStateException("authorization storage unavailable");
                });
        assertThatThrownBy(() -> controller.getUserFiles(42L)).isInstanceOf(IllegalStateException.class)
                .hasMessage("authorization storage unavailable");
        assertThat(com.auraboot.framework.meta.service.impl.DynamicDataQueryScope.isActive()).isFalse();
    }

    @Test void fileListDoesNotCloseAnExistingCallerMetadataScope() {
        when(fileService.getFilesByUserId(42L)).thenReturn(List.of());
        try (var parent = com.auraboot.framework.meta.service.impl.DynamicDataQueryScope.open()) {
            assertThat(controller.getUserFiles(42L).getData()).isEmpty();
            assertThat(com.auraboot.framework.meta.service.impl.DynamicDataQueryScope.isActive()).isTrue();
        }
        assertThat(com.auraboot.framework.meta.service.impl.DynamicDataQueryScope.isActive()).isFalse();
    }

    @Test void machineFileListDoesNotOpenAHumanMetadataScope() {
        var principal = new com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal(
                7L, "machine-pid", "machine", java.util.Set.of());
        when(fileService.getFilesByUserId(null)).thenAnswer(call -> {
            assertThat(com.auraboot.framework.meta.service.impl.DynamicDataQueryScope.isActive()).isFalse();
            return List.of();
        });
        try (var machine = com.auraboot.framework.application.security.MachineDataAccessContext.begin(principal)) {
            assertThat(controller.getUserFiles(null).getData()).isEmpty();
        }
        assertThat(com.auraboot.framework.meta.service.impl.DynamicDataQueryScope.isActive()).isFalse();
    }

    @Test void ownerFileMetadataListDoesNotRevealFilesWithARevokedAdditionalRelation() {
        var own=storedFile("own.txt","text/plain","/tmp/own");own.setPid("own-file");
        var hidden=storedFile("hidden.txt","text/plain","/tmp/hidden");hidden.setPid("hidden-file");
        var relation=new FileRelationEntity().setEntityType("bundle").setEntityId("restricted-bundle").setFieldName("original");
        when(fileService.getFilesByUserId(42L)).thenReturn(List.of(own,hidden));
        when(fileService.getFileRelations("own-file")).thenReturn(List.of());
        when(fileService.getFileRelations("hidden-file")).thenReturn(List.of(relation));
        when(dataAccessAuthorizationHelper.authorizeRecordId(eq("bundle"),eq("read"),eq("restricted-bundle"),any()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("revoked"));
        assertThat(controller.getUserFiles(42L).getData()).extracting(com.auraboot.framework.file.dto.FileUploadResponseDTO::getFileId).containsExactly("own-file");
    }
    @Test void relationFileMetadataListChecksAllOtherRelationsBeforeReturningMetadata() {
        var file=storedFile("hidden.txt","text/plain","/tmp/hidden");
        var first=new FileRelationEntity().setEntityType("bundle").setEntityId("allowed-bundle").setFieldName("original");
        var other=new FileRelationEntity().setEntityType("bundle").setEntityId("restricted-bundle").setFieldName("original");
        when(dataAccessAuthorizationHelper.authorizeRecordId(eq("bundle"),eq("read"),eq("allowed-bundle"),any())).thenReturn(true);
        when(dataAccessAuthorizationHelper.authorizeRecordId(eq("bundle"),eq("read"),eq("restricted-bundle"),any()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("revoked"));
        when(fileService.getFilesByEntity("bundle","allowed-bundle")).thenReturn(List.of(file));
        when(fileService.getFilesByEntityAndField("bundle","allowed-bundle","original")).thenReturn(List.of(file));
        when(fileService.getFileRelations("file-pid")).thenReturn(List.of(first,other));
        com.auraboot.framework.application.tenant.MetaContext.setContext(7L,42L,"actor","Actor");
        try {
            assertThat(controller.getEntityFiles("bundle","allowed-bundle").getData()).isEmpty();
            assertThat(controller.getEntityFieldFiles("bundle","allowed-bundle","original").getData()).isEmpty();
        } finally { com.auraboot.framework.application.tenant.MetaContext.clear(); }
    }

    private static void assertPermission(String methodName, String expected, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = FileUploadController.class.getDeclaredMethod(methodName, parameterTypes);
        RequirePermission annotation = method.getAnnotation(RequirePermission.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo(expected);
    }

    private FileEntity storedFile(String originalName, String mimeType, String localPath) {
        FileEntity file = new FileEntity();
        file.setPid("file-pid");
        file.setOriginalName(originalName);
        file.setMimeType(mimeType);
        file.setLocalPath(localPath);
        file.setCreatedBy(42L);
        return file;
    }
}
