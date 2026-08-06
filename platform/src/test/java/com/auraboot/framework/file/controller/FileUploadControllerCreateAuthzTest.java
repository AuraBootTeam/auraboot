package com.auraboot.framework.file.controller;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.file.dto.FileInfoRequestDTO;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Capability-boundary test for POST /api/file/create.
 *
 * <p>A client-supplied storage key is not proof of object ownership. Cross-tenant checks are
 * insufficient because an attacker can alias another user's object inside the same tenant.
 * Metadata-only creation therefore stays disabled until the host issues upload-session
 * capabilities; multipart upload remains the canonical create path.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileUploadController /create metadata alias guard")
class FileUploadControllerCreateAuthzTest {

    @Mock
    private FileService fileService;
    @Mock
    private StorageProvider storageProvider;

    @InjectMocks
    private FileUploadController controller;

    @Test
    @DisplayName("registering any client-supplied storage key is rejected")
    void create_metadataOnlyAlias_rejected() {
        FileInfoRequestDTO dto = new FileInfoRequestDTO();
        dto.setFileName("01HZZZZZZZZZZZZZZZZZZZZZZZZ.xlsx");

        assertThrows(BusinessException.class, () -> controller.create(dto, 100L));
        verifyNoInteractions(fileService);
    }
}
