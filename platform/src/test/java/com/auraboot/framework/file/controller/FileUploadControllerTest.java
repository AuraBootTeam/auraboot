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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    private MockMvc mvc;
    private FileUploadController controller;

    @BeforeEach
    void setUp() {
        controller = new FileUploadController();
        ReflectionTestUtils.setField(controller, "fileService", fileService);
        ReflectionTestUtils.setField(controller, "storageProvider", storageProvider);
        ReflectionTestUtils.setField(controller, "dynamicDataService", dynamicDataService);
        ReflectionTestUtils.setField(controller, "dataAccessAuthorizationHelper", dataAccessAuthorizationHelper);
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
    void downloadFile_nonImage_keepsAttachmentDisposition() throws Exception {
        FileEntity file = storedFile("quote.pdf", "application/pdf", "/tmp/quote.pdf");
        when(fileService.getFileById("file-pid")).thenReturn(file);
        when(storageProvider.download("/tmp/quote.pdf"))
                .thenReturn(new ByteArrayInputStream("pdf".getBytes(StandardCharsets.UTF_8)));

        mvc.perform(get("/api/file/download/file-pid"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"quote.pdf\""))
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
