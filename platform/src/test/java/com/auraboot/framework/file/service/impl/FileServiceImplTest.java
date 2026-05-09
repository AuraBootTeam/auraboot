package com.auraboot.framework.file.service.impl;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.file.constant.StorageType;
import com.auraboot.framework.file.dao.mapper.FileMapper;
import com.auraboot.framework.file.dao.mapper.FileRelationMapper;
import com.auraboot.framework.file.dto.FileRelationRequestDTO;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.entity.FileRelationEntity;
import com.auraboot.framework.infrastructure.storage.CdnUrlRewriter;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FileServiceImpl}.
 *
 * <p>Tests focus on validation, lookup, deletion authorization, download URL
 * generation, and file-relation handling. Storage I/O is fully mocked via
 * {@link StorageProvider}.
 */
@ExtendWith(MockitoExtension.class)
class FileServiceImplTest {

    @Mock
    private FileMapper fileMapper;

    @Mock
    private FileRelationMapper fileRelationMapper;

    @Mock
    private StorageProvider storageProvider;

    @Mock
    private CdnUrlRewriter cdnUrlRewriter;

    @InjectMocks
    private FileServiceImpl fileService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(fileService, "baseUrl", "http://localhost:8080");
        // cdnUrlRewriter is @Autowired(required=false); InjectMocks will set it.
        // Explicit null-out to default to "no CDN" for most tests.
        ReflectionTestUtils.setField(fileService, "cdnUrlRewriter", null);
    }

    // ----- uploadFile -----

    @Test
    void uploadFile_emptyFile_throwsBusinessException() {
        MultipartFile empty = new MockMultipartFile("file", "x.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> fileService.uploadFile(empty, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("File upload failed");
    }

    @Test
    void uploadFile_disallowedMimeType_throwsBusinessException() {
        MultipartFile bad = new MockMultipartFile(
                "file", "x.exe", "application/x-msdownload", "data".getBytes());

        assertThatThrownBy(() -> fileService.uploadFile(bad, 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void uploadFile_blockedExtension_throwsBusinessException() {
        // Use an allowed MIME type but a blocked filename extension
        MultipartFile bad = new MockMultipartFile(
                "file", "evil.sh", "text/plain", "echo".getBytes());

        assertThatThrownBy(() -> fileService.uploadFile(bad, 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void uploadFile_validImage_persistsAndReturnsResponse() {
        MultipartFile good = new MockMultipartFile(
                "file", "pic.png", "image/png", "img-bytes".getBytes());

        when(storageProvider.upload(anyString(), any(), anyLong(), anyString()))
                .thenReturn("/uploads/pic.png");
        when(storageProvider.type()).thenReturn(StorageType.LOCAL);

        var resp = fileService.uploadFile(good, 42L);

        assertThat(resp).isNotNull();
        assertThat(resp.getFileId()).isNotBlank();
        assertThat(resp.getOriginalName()).isEqualTo("pic.png");
        assertThat(resp.getStorageType()).isEqualTo(StorageType.LOCAL);
        verify(fileMapper).insert(any(FileEntity.class));
    }

    @Test
    void uploadFiles_iteratesAndReturnsList() {
        MultipartFile a = new MockMultipartFile("a", "a.png", "image/png", "a".getBytes());
        MultipartFile b = new MockMultipartFile("b", "b.png", "image/png", "b".getBytes());

        when(storageProvider.upload(anyString(), any(), anyLong(), anyString()))
                .thenReturn("/p");
        when(storageProvider.type()).thenReturn(StorageType.LOCAL);

        var responses = fileService.uploadFiles(new MultipartFile[]{a, b}, 42L);

        assertThat(responses).hasSize(2);
        verify(fileMapper, times(2)).insert(any(FileEntity.class));
    }

    // ----- findByPid / getFileById / getFilesByUserId -----

    @Test
    void findByPid_delegatesToMapper() {
        FileEntity entity = new FileEntity();
        entity.setPid("p1");
        when(fileMapper.selectOne(any(QueryWrapper.class))).thenReturn(entity);

        FileEntity result = fileService.findByPid("p1");
        assertThat(result).isSameAs(entity);
    }

    @Test
    void getFileById_pidHit_returnsEntity() {
        FileEntity entity = new FileEntity();
        entity.setPid("p1");
        when(fileMapper.selectOne(any(QueryWrapper.class))).thenReturn(entity);

        FileEntity result = fileService.getFileById("p1");

        assertThat(result).isSameAs(entity);
        verify(fileMapper, never()).selectById(anyString());
    }

    @Test
    void getFileById_pidMiss_fallsBackToSelectById() {
        when(fileMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        FileEntity entity = new FileEntity();
        when(fileMapper.selectById((java.io.Serializable) "123")).thenReturn(entity);

        FileEntity result = fileService.getFileById("123");
        assertThat(result).isSameAs(entity);
    }

    @Test
    void getFilesByUserId_delegatesToMapper() {
        FileEntity e = new FileEntity();
        when(fileMapper.selectByCreatedBy(7L)).thenReturn(List.of(e));

        List<FileEntity> result = fileService.getFilesByUserId(7L);
        assertThat(result).containsExactly(e);
    }

    // ----- deleteFile -----

    @Test
    void deleteFile_notFound_throwsBusinessException() {
        when(fileMapper.selectById((java.io.Serializable) "missing")).thenReturn(null);

        assertThatThrownBy(() -> fileService.deleteFile("missing", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("File not found");
    }

    @Test
    void deleteFile_notOwner_throwsBusinessException() {
        FileEntity entity = new FileEntity();
        entity.setId(1L);
        entity.setCreatedBy(99L); // different from caller
        entity.setFileName("file.png");
        when(fileMapper.selectById((java.io.Serializable) "1")).thenReturn(entity);

        assertThatThrownBy(() -> fileService.deleteFile("1", 42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("permission");
    }

    @Test
    void deleteFile_owner_softDeletesAndCallsStorage() {
        FileEntity entity = new FileEntity();
        entity.setId(1L);
        entity.setCreatedBy(42L);
        entity.setFileName("k1");
        when(fileMapper.selectById((java.io.Serializable) "1")).thenReturn(entity);
        when(fileMapper.deleteById(1L)).thenReturn(1);

        boolean ok = fileService.deleteFile("1", 42L);

        assertThat(ok).isTrue();
        verify(storageProvider).delete("k1");
        verify(fileMapper).updateById(entity);
        verify(fileMapper).deleteById(1L);
    }

    @Test
    void deleteFile_storageDeleteThrows_swallowedAndContinues() {
        FileEntity entity = new FileEntity();
        entity.setId(1L);
        entity.setCreatedBy(42L);
        entity.setFileName("k1");
        when(fileMapper.selectById((java.io.Serializable) "1")).thenReturn(entity);
        lenient().doThrow(new RuntimeException("S3 unreachable"))
                .when(storageProvider).delete("k1");
        when(fileMapper.deleteById(1L)).thenReturn(1);

        boolean ok = fileService.deleteFile("1", 42L);
        assertThat(ok).isTrue();
    }

    // ----- file relations -----

    @Test
    void createFileRelation_removesPriorAndInserts() {
        FileRelationRequestDTO req = new FileRelationRequestDTO();
        req.setEntityType("user");
        req.setEntityId("100");
        req.setFieldName("avatar");
        req.setFileIds(new String[]{"f1", "f2"});

        fileService.createFileRelation(req);

        // Removal step (delete with QueryWrapper) + 2 inserts
        verify(fileRelationMapper).delete(any(QueryWrapper.class));
        verify(fileRelationMapper, times(2)).insert(any(FileRelationEntity.class));
    }

    @Test
    void getFilesByEntity_emptyIds_returnsEmpty() {
        when(fileRelationMapper.findFileIdsByEntity("user", "1")).thenReturn(List.of());

        List<FileEntity> result = fileService.getFilesByEntity("user", "1");
        assertThat(result).isEmpty();
        verify(fileMapper, never()).selectList(any(QueryWrapper.class));
    }

    @Test
    void getFilesByEntity_withIds_queriesMapper() {
        when(fileRelationMapper.findFileIdsByEntity("user", "1")).thenReturn(List.of("f1"));
        FileEntity e = new FileEntity();
        when(fileMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(e));

        List<FileEntity> result = fileService.getFilesByEntity("user", "1");
        assertThat(result).containsExactly(e);
    }

    @Test
    void getFilesByEntityAndField_emptyIds_returnsEmpty() {
        when(fileRelationMapper.findFileIdsByEntityAndField("user", "1", "avatar"))
                .thenReturn(List.of());

        assertThat(fileService.getFilesByEntityAndField("user", "1", "avatar")).isEmpty();
    }

    @Test
    void removeFileRelation_callsDeleteWithWrapper() {
        when(fileRelationMapper.delete(any(QueryWrapper.class))).thenReturn(1);

        boolean ok = fileService.removeFileRelation("user", "1", "avatar");
        assertThat(ok).isTrue();
    }

    // ----- getFileDownloadUrl -----

    @Test
    void getFileDownloadUrl_notFound_returnsNull() {
        when(fileMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        assertThat(fileService.getFileDownloadUrl("missing")).isNull();
    }

    @Test
    void getFileDownloadUrl_cdnConfigured_takesPriority() {
        ReflectionTestUtils.setField(fileService, "cdnUrlRewriter", cdnUrlRewriter);
        FileEntity entity = new FileEntity();
        entity.setFileName("k1");
        entity.setStorageType(StorageType.LOCAL);
        when(fileMapper.selectOne(any(QueryWrapper.class))).thenReturn(entity);
        when(cdnUrlRewriter.rewrite("k1")).thenReturn("https://cdn.example.com/k1");

        String url = fileService.getFileDownloadUrl("p1");
        assertThat(url).isEqualTo("https://cdn.example.com/k1");
    }

    @Test
    void getFileDownloadUrl_localStorage_returnsApiPath() {
        FileEntity entity = new FileEntity();
        entity.setFileName("k1");
        entity.setStorageType(StorageType.LOCAL);
        when(fileMapper.selectOne(any(QueryWrapper.class))).thenReturn(entity);

        String url = fileService.getFileDownloadUrl("p1");
        assertThat(url).contains("/api/file/download/p1");
    }

    @Test
    void getFileDownloadUrl_cloud_usesPresignedUrl() {
        FileEntity entity = new FileEntity();
        entity.setFileName("k1");
        entity.setStorageType(StorageType.S3);
        when(fileMapper.selectOne(any(QueryWrapper.class))).thenReturn(entity);
        when(storageProvider.getPresignedUrl(eq("k1"), any(Duration.class)))
                .thenReturn("https://s3/presigned");

        String url = fileService.getFileDownloadUrl("p1");
        assertThat(url).isEqualTo("https://s3/presigned");
    }

    @Test
    void getFileDownloadUrl_cloud_presignedUnsupported_fallsBackToCloudPath() {
        FileEntity entity = new FileEntity();
        entity.setFileName("k1");
        entity.setStorageType(StorageType.OSS);
        entity.setCloudPath("https://oss/path");
        when(fileMapper.selectOne(any(QueryWrapper.class))).thenReturn(entity);
        when(storageProvider.getPresignedUrl(eq("k1"), any(Duration.class)))
                .thenThrow(new UnsupportedOperationException());

        String url = fileService.getFileDownloadUrl("p1");
        assertThat(url).isEqualTo("https://oss/path");
    }

    // Touch Instant import to avoid unused warning if reorder happens.
    @SuppressWarnings("unused")
    private static final Instant SENTINEL = Instant.EPOCH;
}
