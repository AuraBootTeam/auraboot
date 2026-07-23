package com.auraboot.framework.file.service.impl;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.file.constant.StorageType;
import com.auraboot.framework.file.constant.UploadStatus;
import com.auraboot.framework.file.dao.mapper.FileMapper;
import com.auraboot.framework.file.dao.mapper.FileRelationMapper;
import com.auraboot.framework.file.dto.FileRelationRequestDTO;
import com.auraboot.framework.file.dto.FileUploadResponseDTO;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.entity.FileRelationEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.file.support.FileNameEncodingSupport;
import com.auraboot.framework.infrastructure.storage.CdnUrlRewriter;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.auraboot.framework.exception.BusinessException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * File service implementation backed by {@link StorageProvider} SPI.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final FileMapper fileMapper;
    private final FileRelationMapper fileRelationMapper;
    private final StorageProvider storageProvider;

    @Autowired(required = false)
    private CdnUrlRewriter cdnUrlRewriter;

    @Value("${file.download.base-url:/222}")
    private String baseUrl;

    /** Max upload size: 50 MB */
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    private static final Pattern GENERATED_STORAGE_FILE_NAME =
            Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}(\\.[A-Za-z0-9]{1,16})?");

    /** Allowed MIME types for upload */
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml",
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.ms-excel", "application/msword", "application/vnd.ms-powerpoint",
            "text/plain", "text/csv", "text/markdown", "text/html",
            "application/json", "application/xml",
            "application/x-yaml", "application/yaml", "text/yaml",
            "application/zip", "application/x-zip", "application/x-zip-compressed", "application/gzip",
            "application/vnd.rar", "application/x-rar-compressed", "application/x-7z-compressed"
    );

    /**
     * Extensions we trust by name. The browser-reported Content-Type is
     * client-controlled and unreliable — a RAR archive alone surfaces as
     * {@code application/vnd.rar}, {@code application/x-rar-compressed},
     * {@code application/x-rar} or {@code application/octet-stream} depending on
     * the OS/browser, and manufacturing CAM/EDA files often carry no stable MIME
     * at all. For these known-safe extensions we accept the upload regardless of
     * the reported MIME; dangerous extensions are already rejected up front by
     * {@link #BLOCKED_EXTENSIONS}, which is the real guard.
     */
    private static final Set<String> TRUSTED_EXTENSIONS = Set.of(
            "zip", "rar", "7z", "pcb",
            "gbr", "gtl", "gbl", "gto", "gbo", "gts", "gbs", "gko",
            "gm", "gm1", "gbp", "gdd", "gd1", "g1", "g2",
            "pho", "art", "drl", "xln", "drr", "rep", "extrep",
            "ldp", "apr", "apr_lib", "rul", "pos", "cpl"
    );

    /** Blocked file extensions (executables, scripts) */
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "bat", "cmd", "sh", "ps1", "vbs", "js", "mjs",
            "jsp", "php", "py", "rb", "pl", "cgi", "war", "jar", "class"
    );

    @Override
    public FileEntity findByPid(String pid) {
        QueryWrapper<FileEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(FileEntity::getPid, pid);
        return fileMapper.selectOne(queryWrapper);
    }

    @Override
    public boolean existsStorageKeyInOtherTenants(String storageKey, Long tenantId) {
        return StringUtils.hasText(storageKey)
                && tenantId != null
                && fileMapper.countByFileNameInOtherTenants(storageKey, tenantId) > 0;
    }

    @Override
    public void saveMetadata(FileEntity fileEntity) {
        fileMapper.insert(fileEntity);
    }

    @Override
    public FileUploadResponseDTO uploadFile(MultipartFile file, Long userId) {
        try {
            if (file.isEmpty()) {
                throw new IllegalArgumentException("File must not be empty");
            }

            // Validate file size
            if (file.getSize() > MAX_FILE_SIZE) {
                throw new BusinessException("File too large: max " + (MAX_FILE_SIZE / 1024 / 1024) + "MB");
            }

            // Validate file extension
            String extension = getFileExtension(file.getOriginalFilename());
            if (BLOCKED_EXTENSIONS.contains(extension.toLowerCase())) {
                throw new BusinessException("File extension not allowed: " + extension);
            }

            // Validate the file type from its actual content, not the
            // client-declared Content-Type (which is unreliable and spoofable).
            String contentType = file.getContentType();
            if (!isAllowedUpload(file, extension)) {
                throw new BusinessException("File type not allowed: " + contentType);
            }

            FileEntity fileEntity = createFileEntity(file, userId);
            fileEntity.setPid(UniqueIdGenerator.generate());

            // Delegate to StorageProvider
            String storageKey = fileEntity.getFileName();
            String storedPath = storageProvider.upload(
                    storageKey,
                    file.getInputStream(),
                    file.getSize(),
                    file.getContentType());

            fileEntity.setLocalPath(storedPath);
            fileEntity.setStorageType(storageProvider.type());
            fileEntity.setStatus(UploadStatus.SUCCESS.getCode());

            fileMapper.insert(fileEntity);
            return buildUploadResponse(fileEntity);

        } catch (Exception e) {
            log.error("File upload failed", e);
            throw new BusinessException("File upload failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<FileUploadResponseDTO> uploadFiles(MultipartFile[] files, Long userId) {
        List<FileUploadResponseDTO> responses = new ArrayList<>();
        for (MultipartFile file : files) {
            responses.add(uploadFile(file, userId));
        }
        return responses;
    }

    @Override
    public FileEntity getFileById(String fileId) {
        // Try PID lookup first (ULID string), fallback to numeric ID
        FileEntity entity = findByPid(fileId);
        if (entity != null) {
            return entity;
        }
        entity = findByGeneratedStorageFileName(fileId);
        if (entity != null) {
            return entity;
        }
        if (!isNumericId(fileId)) {
            return null;
        }
        return fileMapper.selectById(fileId);
    }

    @Override
    public List<FileEntity> getFilesByUserId(Long userId) {
        return fileMapper.selectByCreatedBy(userId);
    }

    @Override
    @Transactional
    public boolean deleteFile(String fileId, Long userId) {
        FileEntity fileEntity = findByPid(fileId);
        if (fileEntity == null && isNumericId(fileId)) {
            fileEntity = fileMapper.selectById(fileId);
        }
        if (fileEntity == null) {
            throw new BusinessException("File not found: " + fileId);
        }
        if (!fileEntity.getCreatedBy().equals(userId)) {
            throw new BusinessException("You don't have permission to delete this file");
        }

        // Delete physical file from storage
        try {
            storageProvider.delete(fileEntity.getFileName());
        } catch (Exception e) {
            log.warn("Failed to delete physical file: key={}, error={}", fileEntity.getFileName(), e.getMessage());
        }

        fileEntity.setStatus(UploadStatus.DELETED.getCode());
        fileMapper.updateById(fileEntity);
        return fileMapper.deleteById(fileEntity.getId()) > 0;
    }

    @Override
    @Transactional
    public boolean deleteFiles(String[] fileIds, Long userId) {
        boolean allSuccess = true;
        for (String fileId : fileIds) {
            if (!deleteFile(fileId, userId)) {
                allSuccess = false;
            }
        }
        return allSuccess;
    }

    @Override
    @Transactional
    public boolean createFileRelation(FileRelationRequestDTO request) {
        removeFileRelation(request.getEntityType(), request.getEntityId(), request.getFieldName());

        for (int i = 0; i < request.getFileIds().length; i++) {
            FileRelationEntity relation = new FileRelationEntity();
            relation.setFileId(request.getFileIds()[i]);
            relation.setEntityType(request.getEntityType());
            relation.setEntityId(request.getEntityId());
            relation.setFieldName(request.getFieldName());
            relation.setSortOrder(i);
            fileRelationMapper.insert(relation);
        }
        return true;
    }

    @Override
    public List<FileEntity> getFilesByEntity(String entityType, String entityId) {
        List<String> fileIds = fileRelationMapper.findFileIdsByEntity(entityType, entityId);
        if (fileIds.isEmpty()) {
            return new ArrayList<>();
        }

        QueryWrapper<FileEntity> wrapper = new QueryWrapper<>();
        wrapper.in("id", fileIds);
        wrapper.eq("deleted_flag", false);
        return fileMapper.selectList(wrapper);
    }

    @Override
    public List<FileEntity> getFilesByEntityAndField(String entityType, String entityId, String fieldName) {
        List<String> fileIds = fileRelationMapper.findFileIdsByEntityAndField(entityType, entityId, fieldName);
        if (fileIds.isEmpty()) {
            return new ArrayList<>();
        }

        QueryWrapper<FileEntity> wrapper = new QueryWrapper<>();
        wrapper.in("id", fileIds);
        wrapper.eq("deleted_flag", false);
        return fileMapper.selectList(wrapper);
    }

    @Override
    @Transactional
    public boolean removeFileRelation(String entityType, String entityId, String fieldName) {
        QueryWrapper<FileRelationEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("entity_type", entityType)
               .eq("entity_id", entityId)
               .eq("field_name", fieldName);
        return fileRelationMapper.delete(wrapper) >= 0;
    }

    @Override
    public String getFileDownloadUrl(String fileId) {
        // fileId may be a pid (ULID string) — try findByPid first, fallback to selectById
        FileEntity fileEntity = findByPid(fileId);
        if (fileEntity == null) {
            return null;
        }

        String storageKey = fileEntity.getFileName();

        // CDN takes priority
        if (cdnUrlRewriter != null) {
            return cdnUrlRewriter.rewrite(storageKey);
        }

        if (fileEntity.getStorageType() == StorageType.LOCAL) {
            return baseUrl + "/api/file/download/" + fileId;
        }

        // For cloud providers, try pre-signed URL
        try {
            return storageProvider.getPresignedUrl(storageKey, Duration.ofHours(1));
        } catch (UnsupportedOperationException e) {
            return fileEntity.getCloudPath();
        }
    }

    private FileEntity createFileEntity(MultipartFile file, Long userId) {
        String originalFilename = FileNameEncodingSupport.normalizeOriginalFilename(file.getOriginalFilename());
        String extension = getFileExtension(originalFilename);
        String filename = UniqueIdGenerator.generate() + "." + extension;

        FileEntity fileEntity = new FileEntity();
        fileEntity.setFileName(filename);
        fileEntity.setOriginalName(originalFilename);
        fileEntity.setFileSize(file.getSize());
        fileEntity.setMimeType(file.getContentType());
        fileEntity.setFileExtension(extension);
        fileEntity.setStorageType(storageProvider.type());
        fileEntity.setStatus(UploadStatus.UPLOADING.getCode());
        fileEntity.setCreatedBy(userId);
        fileEntity.setCreatedTime(Instant.now());
        fileEntity.setUpdatedTime(Instant.now());
        fileEntity.setDeletedFlag(false);

        return fileEntity;
    }

    private FileUploadResponseDTO buildUploadResponse(FileEntity fileEntity) {
        FileUploadResponseDTO response = new FileUploadResponseDTO();
        response.setFileId(fileEntity.getPid());
        response.setOriginalName(fileEntity.getOriginalName());
        response.setFileSize(fileEntity.getFileSize());
        response.setUrl(getFileDownloadUrl(fileEntity.getPid()));
        response.setStorageType(fileEntity.getStorageType());
        response.setStatus(fileEntity.getStatus());
        response.setUploadTime(com.auraboot.framework.common.util.DateUtil.toUtcLocalDateTime(fileEntity.getCreatedTime()));
        return response;
    }

    private String getFileExtension(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        int lastDotIndex = filename.lastIndexOf('.');
        return lastDotIndex > 0 ? filename.substring(lastDotIndex + 1) : "";
    }

    private enum ContentSignature { ALLOWED, DANGEROUS, UNKNOWN }

    /** Bytes to inspect for a magic-number signature (covers our longest signature). */
    private static final int SIGNATURE_PEEK_BYTES = 16;

    /**
     * Decide whether an upload is allowed based on its <em>actual bytes</em>
     * rather than the client-declared {@code Content-Type} or file extension,
     * both of which are client-controlled and unreliable (the same RAR archive
     * is variously labelled {@code application/vnd.rar}, {@code x-rar-compressed}
     * or {@code x-rar}). The content signature is authoritative:
     * <ul>
     *   <li>a recognised executable is rejected no matter how it is named/labelled;</li>
     *   <li>a recognised archive/document/image is accepted regardless of the label.</li>
     * </ul>
     * Only when the content has no decisive magic number — genuinely format-less
     * files such as CAM/EDA (Gerber, drill), CSV or plain text — do we fall back
     * to the extension and MIME allowlists.
     */
    private boolean isAllowedUpload(MultipartFile file, String extension) {
        ContentSignature signature = detectSignature(readHeader(file, SIGNATURE_PEEK_BYTES));
        if (signature == ContentSignature.DANGEROUS) {
            return false;
        }
        if (signature == ContentSignature.ALLOWED) {
            return true;
        }
        // No decisive magic number: fall back to name/label allowlists.
        if (extension != null && TRUSTED_EXTENSIONS.contains(extension.toLowerCase())) {
            return true;
        }
        String contentType = file.getContentType();
        return contentType != null && ALLOWED_MIME_TYPES.contains(contentType.toLowerCase());
    }

    private static byte[] readHeader(MultipartFile file, int max) {
        try (InputStream in = file.getInputStream()) {
            byte[] buffer = new byte[max];
            int read = 0;
            int r;
            while (read < max && (r = in.read(buffer, read, max - read)) != -1) {
                read += r;
            }
            return read == max ? buffer : Arrays.copyOf(buffer, read);
        } catch (IOException e) {
            // Unreadable header → treat as no signature; name/label fallback applies.
            return new byte[0];
        }
    }

    private static ContentSignature detectSignature(byte[] head) {
        // Executables and other code artifacts — rejected regardless of name/label.
        if (startsWith(head, 0x4D, 0x5A)                            // "MZ"  DOS/PE (exe/dll)
                || startsWith(head, 0x7F, 0x45, 0x4C, 0x46)         // ELF (Linux binaries)
                || startsWith(head, 0xCA, 0xFE, 0xBA, 0xBE)         // Java .class / Mach-O fat binary
                || startsWith(head, 0xFE, 0xED, 0xFA, 0xCE)         // Mach-O 32-bit
                || startsWith(head, 0xFE, 0xED, 0xFA, 0xCF)         // Mach-O 64-bit
                || startsWith(head, 0xCE, 0xFA, 0xED, 0xFE)         // Mach-O 32-bit (reverse)
                || startsWith(head, 0xCF, 0xFA, 0xED, 0xFE)) {      // Mach-O 64-bit (reverse)
            return ContentSignature.DANGEROUS;
        }
        // Known-good binary containers, documents and images — accepted on content.
        if (startsWith(head, 0x50, 0x4B, 0x03, 0x04)               // ZIP / OOXML (docx/xlsx/pptx)
                || startsWith(head, 0x50, 0x4B, 0x05, 0x06)         // ZIP (empty archive)
                || startsWith(head, 0x50, 0x4B, 0x07, 0x08)         // ZIP (spanned)
                || startsWith(head, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)   // "Rar!" RAR v4/v5
                || startsWith(head, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)   // 7z
                || startsWith(head, 0x1F, 0x8B)                     // gzip
                || startsWith(head, 0x25, 0x50, 0x44, 0x46)         // "%PDF"
                || startsWith(head, 0x89, 0x50, 0x4E, 0x47)         // PNG
                || startsWith(head, 0xFF, 0xD8, 0xFF)               // JPEG
                || startsWith(head, 0x47, 0x49, 0x46, 0x38)         // "GIF8"
                || startsWith(head, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)   // OLE2 (legacy Office)
                || isWebp(head)) {                                 // RIFF....WEBP
            return ContentSignature.ALLOWED;
        }
        return ContentSignature.UNKNOWN;
    }

    private static boolean isWebp(byte[] head) {
        return startsWith(head, 0x52, 0x49, 0x46, 0x46)            // "RIFF"
                && head.length >= 12
                && (head[8] & 0xFF) == 0x57 && (head[9] & 0xFF) == 0x45   // "WE"
                && (head[10] & 0xFF) == 0x42 && (head[11] & 0xFF) == 0x50; // "BP"
    }

    private static boolean startsWith(byte[] data, int... signature) {
        if (data.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((data[i] & 0xFF) != (signature[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private FileEntity findByGeneratedStorageFileName(String fileId) {
        if (!StringUtils.hasText(fileId) || !GENERATED_STORAGE_FILE_NAME.matcher(fileId).matches()) {
            return null;
        }
        FileEntity entity = findByFileName(fileId);
        if (entity != null || fileId.contains(".")) {
            return entity;
        }
        return findByFileName(fileId + ".svg");
    }

    private FileEntity findByFileName(String fileName) {
        QueryWrapper<FileEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(FileEntity::getFileName, fileName);
        return fileMapper.selectOne(queryWrapper);
    }

    private boolean isNumericId(String value) {
        return StringUtils.hasText(value) && value.chars().allMatch(Character::isDigit);
    }
}
