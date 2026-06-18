package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.file.dto.FileRelationRequestDTO;
import com.auraboot.framework.file.dto.FileUploadResponseDTO;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.service.FileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * G-B4: BpmAttachmentController wiring over the platform file infrastructure.
 *
 * <p>The controller is a thin BPM-scoped adapter over {@link FileService} (whose storage
 * round-trip is covered by {@code FileServiceImplTest}). This locks in the BPM-specific contract:
 * the right entity types are used, an attachment links a file to the task/process via a file
 * relation, the list endpoint maps file entities, and delete delegates to the file service.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BpmAttachmentController wiring (G-B4)")
class BpmAttachmentControllerTest {

    @Mock
    private FileService fileService;

    @InjectMocks
    private BpmAttachmentController controller;

    @Test
    @DisplayName("upload links the file to the task via a BPM_TASK relation and returns metadata")
    void uploadTaskAttachment_linksFileAndReturnsMetadata() {
        MultipartFile file = new MockMultipartFile("file", "spec.pdf", "application/pdf", "bytes".getBytes());
        FileUploadResponseDTO uploaded = new FileUploadResponseDTO();
        uploaded.setFileId("file-1");
        uploaded.setOriginalName("spec.pdf");
        uploaded.setFileSize(5L);
        uploaded.setMimeType("application/pdf");
        when(fileService.uploadFile(any(), eq(42L))).thenReturn(uploaded);
        when(fileService.createFileRelation(any())).thenReturn(true);

        ApiResponse<Map<String, Object>> resp = controller.uploadTaskAttachment("task-9", file, 42L);

        ArgumentCaptor<FileRelationRequestDTO> relationCaptor = ArgumentCaptor.forClass(FileRelationRequestDTO.class);
        verify(fileService).createFileRelation(relationCaptor.capture());
        FileRelationRequestDTO relation = relationCaptor.getValue();
        assertThat(relation.getEntityType()).isEqualTo("BPM_TASK");
        assertThat(relation.getEntityId()).isEqualTo("task-9");
        assertThat(relation.getFileIds()).containsExactly("file-1");

        assertThat(resp.getData()).containsEntry("fileId", "file-1");
        assertThat(resp.getData()).containsEntry("originalName", "spec.pdf");
    }

    @Test
    @DisplayName("process upload uses a BPM_PROCESS relation")
    void uploadProcessAttachment_usesProcessEntityType() {
        MultipartFile file = new MockMultipartFile("file", "x.pdf", "application/pdf", "b".getBytes());
        FileUploadResponseDTO uploaded = new FileUploadResponseDTO();
        uploaded.setFileId("file-2");
        when(fileService.uploadFile(any(), any())).thenReturn(uploaded);
        when(fileService.createFileRelation(any())).thenReturn(true);

        controller.uploadProcessAttachment("pi-7", file, 1L);

        ArgumentCaptor<FileRelationRequestDTO> relationCaptor = ArgumentCaptor.forClass(FileRelationRequestDTO.class);
        verify(fileService).createFileRelation(relationCaptor.capture());
        assertThat(relationCaptor.getValue().getEntityType()).isEqualTo("BPM_PROCESS");
        assertThat(relationCaptor.getValue().getEntityId()).isEqualTo("pi-7");
    }

    @Test
    @DisplayName("list maps file entities related to the task")
    void getTaskAttachments_mapsRelatedFiles() {
        FileEntity file = new FileEntity();
        file.setPid("pub-1");
        file.setOriginalName("design.pdf");
        file.setFileName("stored.pdf");
        file.setFileSize(11L);
        file.setMimeType("application/pdf");
        when(fileService.getFilesByEntity("BPM_TASK", "task-9")).thenReturn(List.of(file));
        when(fileService.getFileDownloadUrl("pub-1")).thenReturn("/api/file/pub-1");

        ApiResponse<List<Map<String, Object>>> resp = controller.getTaskAttachments("task-9");

        assertThat(resp.getData()).hasSize(1);
        Map<String, Object> item = resp.getData().get(0);
        assertThat(item).containsEntry("fileId", "pub-1");
        assertThat(item).containsEntry("originalName", "design.pdf");
        assertThat(item).containsEntry("downloadUrl", "/api/file/pub-1");
    }

    @Test
    @DisplayName("delete delegates to the file service")
    void deleteAttachment_delegatesToFileService() {
        when(fileService.deleteFile("pub-1", 42L)).thenReturn(true);

        controller.deleteAttachment("pub-1", 42L);

        verify(fileService).deleteFile("pub-1", 42L);
    }
}
