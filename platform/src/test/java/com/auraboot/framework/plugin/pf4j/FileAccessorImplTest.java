package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.file.dto.FileUploadResponseDTO;
import com.auraboot.framework.file.dto.FileRelationRequestDTO;
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
import java.util.List;

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
        entity.setStatus("success");
        when(fileService.getFileById("file-pid")).thenReturn(entity);
        when(storageProvider.download("stored.xlsx")).thenReturn(new ByteArrayInputStream(bytes));

        FileAccessor accessor = new FileAccessorImpl(fileService, storageProvider, 42L);

        assertThat(accessor.open("file-pid").readAllBytes()).isEqualTo(bytes);
        verify(storageProvider).download("stored.xlsx");
    }

    @Test
    void describe_returns_storage_opaque_metadata_for_a_public_file_pid() {
        FileEntity entity = file("file-pid", "stored-key.xlsx", "customer-bom.xlsx");
        entity.setFileSize(123L);
        entity.setMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        entity.setCreatedBy(42L);
        entity.setStatus("active");
        when(fileService.getFileById("file-pid")).thenReturn(entity);

        FileAccessor accessor = new FileAccessorImpl(fileService, storageProvider, 42L);

        assertThat(accessor.describe("file-pid")).isEqualTo(new FileAccessor.FileMetadata(
                "file-pid",
                "customer-bom.xlsx",
                123L,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "42",
                "active"));
    }

    @Test
    void describe_rejects_storage_keys_and_internal_aliases() {
        when(fileService.getFileById("stored-key.xlsx"))
                .thenReturn(file("file-pid", "stored-key.xlsx", "customer-bom.xlsx"));
        FileAccessor accessor = new FileAccessorImpl(fileService, storageProvider, 42L);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> accessor.describe("stored-key.xlsx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public file pid");
    }

    @Test
    void open_rejects_storageAliasesAndUnfinalizedMetadata() {
        FileEntity alias = file("file-pid", "stored-key.xlsx", "customer-bom.xlsx");
        alias.setStatus("success");
        when(fileService.getFileById("stored-key.xlsx")).thenReturn(alias);
        FileEntity active = file("active-pid", "active-key.xlsx", "active.xlsx");
        active.setStatus("active");
        when(fileService.getFileById("active-pid")).thenReturn(active);
        FileAccessor accessor = new FileAccessorImpl(fileService, storageProvider, 42L);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> accessor.open("stored-key.xlsx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public file pid");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> accessor.open("active-pid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not finalized");
    }

    @Test
    void isLinkedTo_requires_the_exact_public_pid_in_the_business_field_relation() {
        FileEntity target = file("file-pid", "stored-key.xlsx", "customer-bom.xlsx");
        when(fileService.getFileById("file-pid")).thenReturn(target);
        when(fileService.getFilesByEntityAndField(
                "crm_customer_request_common", "request-pid", "qdp_source_files"))
                .thenReturn(List.of(target));
        FileAccessor accessor = new FileAccessorImpl(fileService, storageProvider, 42L);

        assertThat(accessor.isLinkedTo(
                "file-pid", "crm_customer_request_common", "request-pid", "qdp_source_files"))
                .isTrue();
    }

    @Test
    void retain_applies_monotonic_host_retention_to_the_exact_public_pid() {
        FileEntity target = file("file-pid", "stored-key.xlsx", "customer-bom.xlsx");
        target.setStatus("success");
        when(fileService.getFileById("file-pid")).thenReturn(target);
        when(fileService.lockRetention("file-pid")).thenReturn(true);
        FileAccessor accessor = new FileAccessorImpl(fileService, storageProvider, 42L);

        assertThat(accessor.retain("file-pid")).isTrue();
        verify(fileService).lockRetention("file-pid");
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

    @Test
    void saveAndLink_registersTheGeneratedFileAgainstTheExactBusinessField() {
        byte[] bytes = "governed-report".getBytes(StandardCharsets.UTF_8);
        FileUploadResponseDTO response = new FileUploadResponseDTO();
        response.setFileId("report-file-pid");
        response.setOriginalName("recall-report.json");
        response.setFileSize((long) bytes.length);
        response.setUrl("/api/file/download/report-file-pid");
        when(fileService.uploadFile(org.mockito.ArgumentMatchers.any(MultipartFile.class), eq(42L)))
                .thenReturn(response);
        when(fileService.createFileRelation(org.mockito.ArgumentMatchers.any(FileRelationRequestDTO.class), eq(42L)))
                .thenReturn(true);
        FileAccessor accessor = new FileAccessorImpl(fileService, storageProvider, 42L);

        FileAccessor.SavedFile saved = accessor.saveAndLink(
                "recall-report.json", "application/json", bytes,
                "qtr_recall_exercise", "exercise-pid", "qtr_re_report_ref");

        assertThat(saved.fileId()).isEqualTo("report-file-pid");
        ArgumentCaptor<FileRelationRequestDTO> relationCaptor = ArgumentCaptor.forClass(FileRelationRequestDTO.class);
        verify(fileService).createFileRelation(relationCaptor.capture(), eq(42L));
        FileRelationRequestDTO relation = relationCaptor.getValue();
        assertThat(relation.getEntityType()).isEqualTo("qtr_recall_exercise");
        assertThat(relation.getEntityId()).isEqualTo("exercise-pid");
        assertThat(relation.getFieldName()).isEqualTo("qtr_re_report_ref");
        assertThat(relation.getFileIds()).containsExactly("report-file-pid");
    }

    @Test
    void saveAndLink_failsClosedWhenTheHostCannotPersistTheRelation() {
        byte[] bytes = "governed-report".getBytes(StandardCharsets.UTF_8);
        FileUploadResponseDTO response = new FileUploadResponseDTO();
        response.setFileId("report-file-pid");
        response.setOriginalName("recall-report.json");
        response.setFileSize((long) bytes.length);
        when(fileService.uploadFile(org.mockito.ArgumentMatchers.any(MultipartFile.class), eq(42L)))
                .thenReturn(response);
        when(fileService.createFileRelation(org.mockito.ArgumentMatchers.any(FileRelationRequestDTO.class), eq(42L)))
                .thenReturn(false);
        FileAccessor accessor = new FileAccessorImpl(fileService, storageProvider, 42L);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> accessor.saveAndLink(
                        "recall-report.json", "application/json", bytes,
                        "qtr_recall_exercise", "exercise-pid", "qtr_re_report_ref"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("relation");
    }

    private static FileEntity file(String pid, String storageKey, String originalName) {
        FileEntity entity = new FileEntity();
        entity.setPid(pid);
        entity.setFileName(storageKey);
        entity.setOriginalName(originalName);
        return entity;
    }
}
