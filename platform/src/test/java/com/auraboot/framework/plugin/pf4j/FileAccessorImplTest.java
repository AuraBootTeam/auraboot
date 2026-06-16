package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.file.dto.FileUploadResponseDTO;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.plugin.extension.FileAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileAccessorImplTest {

    @Mock
    private FileService fileService;

    @Mock
    private StorageProvider storageProvider;

    @Test
    void open_reads_file_bytes_from_platform_storage_by_file_id() throws Exception {
        byte[] bytes = "xlsx-bytes".getBytes(StandardCharsets.UTF_8);
        FileEntity entity = new FileEntity();
        entity.setPid("file-pid");
        entity.setFileName("stored.xlsx");
        entity.setLocalPath("/server/internal/path/stored.xlsx");
        when(fileService.getFileById("file-pid")).thenReturn(entity);
        when(storageProvider.download("stored.xlsx")).thenReturn(new ByteArrayInputStream(bytes));

        FileAccessor accessor = new FileAccessorImpl(fileService, storageProvider, 42L);

        assertThat(accessor.open("file-pid").readAllBytes()).isEqualTo(bytes);
        verify(storageProvider).download("stored.xlsx");
    }

    @Test
    void save_uploads_generated_bytes_and_returns_platform_file_id() throws Exception {
        byte[] bytes = "standard-bom".getBytes(StandardCharsets.UTF_8);
        FileUploadResponseDTO response = new FileUploadResponseDTO();
        response.setFileId("export-file-pid");
        response.setOriginalName("standard-bom.xlsx");
        response.setFileSize((long) bytes.length);
        response.setUrl("/api/file/download/export-file-pid");
        when(fileService.uploadFile(org.mockito.ArgumentMatchers.any(MultipartFile.class), eq(42L)))
                .thenReturn(response);

        FileAccessor accessor = new FileAccessorImpl(fileService, storageProvider, 42L);
        FileAccessor.SavedFile saved = accessor.save(
                "standard-bom.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                bytes);

        assertThat(saved.fileId()).isEqualTo("export-file-pid");
        assertThat(saved.originalName()).isEqualTo("standard-bom.xlsx");
        assertThat(saved.size()).isEqualTo(bytes.length);
        assertThat(saved.url()).isEqualTo("/api/file/download/export-file-pid");

        ArgumentCaptor<MultipartFile> fileCaptor = ArgumentCaptor.forClass(MultipartFile.class);
        verify(fileService).uploadFile(fileCaptor.capture(), eq(42L));
        MultipartFile uploaded = fileCaptor.getValue();
        assertThat(uploaded.getOriginalFilename()).isEqualTo("standard-bom.xlsx");
        assertThat(uploaded.getContentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(uploaded.getBytes()).isEqualTo(bytes);
    }

    @Test
    void save_recoversMojibakeOriginalNameBeforeUpload() throws Exception {
        byte[] bytes = "standard-bom".getBytes(StandardCharsets.UTF_8);
        FileUploadResponseDTO response = new FileUploadResponseDTO();
        response.setFileId("export-file-pid");
        response.setOriginalName("原始-BOM.xlsx");
        response.setFileSize((long) bytes.length);
        response.setUrl("/api/file/download/export-file-pid");
        when(fileService.uploadFile(org.mockito.ArgumentMatchers.any(MultipartFile.class), eq(42L)))
                .thenReturn(response);

        FileAccessor accessor = new FileAccessorImpl(fileService, storageProvider, 42L);
        accessor.save(
                "å\u008E\u009Få§\u008B-BOM.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                bytes);

        ArgumentCaptor<MultipartFile> fileCaptor = ArgumentCaptor.forClass(MultipartFile.class);
        verify(fileService).uploadFile(fileCaptor.capture(), eq(42L));
        assertThat(fileCaptor.getValue().getOriginalFilename()).isEqualTo("原始-BOM.xlsx");
    }
}
