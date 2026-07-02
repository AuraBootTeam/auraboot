package com.auraboot.framework.file.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.file.dao.mapper.FileMapper;
import com.auraboot.framework.file.dto.FileInfoRequestDTO;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Object-level tenant isolation test for POST /api/file/create.
 *
 * <p>Security regression: the metadata-only create endpoint let a client register an
 * arbitrary storage key ({@code fileName}); object-read sinks (FileAccessorImpl /
 * FileImageBridge) download by that key with no tenant prefix on a shared bucket, so a
 * client could claim another tenant's key and read its object. Create now rejects a key
 * already owned by a different tenant.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileUploadController /create cross-tenant key guard")
class FileUploadControllerCreateAuthzTest {

    @Mock
    private FileMapper fileMapper;
    @Mock
    private FileService fileService;
    @Mock
    private StorageProvider storageProvider;

    @InjectMocks
    private FileUploadController controller;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(1L, 100L, "u-100", "user100");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("registering a storage key owned by another tenant is rejected (no insert)")
    void create_crossTenantKey_rejected() {
        String victimKey = "01HZZZZZZZZZZZZZZZZZZZZZZZZ.xlsx";
        when(fileMapper.countByFileNameInOtherTenants(eq(victimKey), eq(1L))).thenReturn(1);

        FileInfoRequestDTO dto = new FileInfoRequestDTO();
        dto.setFileName(victimKey);

        assertThrows(IllegalArgumentException.class, () -> controller.create(dto, 100L));
        verify(fileMapper, never()).insert(any(com.auraboot.framework.file.entity.FileEntity.class));
    }
}
