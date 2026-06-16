package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.file.dto.FileUploadResponseDTO;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.file.support.FileNameEncodingSupport;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.plugin.extension.FileAccessor;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * Host-side implementation of the plugin file byte bridge.
 */
@RequiredArgsConstructor
public class FileAccessorImpl implements FileAccessor {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final FileService fileService;
    private final StorageProvider storageProvider;
    private final Long userId;

    @Override
    public InputStream open(String fileId) {
        if (!StringUtils.hasText(fileId)) {
            throw new IllegalArgumentException("fileId is required");
        }
        FileEntity entity = fileService.getFileById(fileId);
        if (entity == null) {
            throw new IllegalArgumentException("File not found: " + fileId);
        }
        String storageKey = firstText(entity.getFileName(), entity.getLocalPath());
        if (!StringUtils.hasText(storageKey)) {
            throw new IllegalStateException("File has no storage key: " + fileId);
        }
        return storageProvider.download(storageKey);
    }

    @Override
    public SavedFile save(String originalName, String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("file bytes must not be empty");
        }
        String safeName = safeOriginalName(originalName);
        String mimeType = StringUtils.hasText(contentType) ? contentType : DEFAULT_CONTENT_TYPE;
        FileUploadResponseDTO response = fileService.uploadFile(
                new ByteArrayMultipartFile("file", safeName, mimeType, bytes),
                userId);
        String responseName = StringUtils.hasText(response.getOriginalName()) ? response.getOriginalName() : safeName;
        long responseSize = response.getFileSize() != null ? response.getFileSize() : bytes.length;
        return new SavedFile(response.getFileId(), responseName, responseSize, response.getUrl());
    }

    private static String firstText(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first;
        }
        return StringUtils.hasText(second) ? second : null;
    }

    private static String safeOriginalName(String originalName) {
        if (!StringUtils.hasText(originalName)) {
            return "generated-file.bin";
        }
        String normalized = originalName.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        String basename = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        return FileNameEncodingSupport.normalizeOriginalFilename(basename);
    }

    private record ByteArrayMultipartFile(
            String name,
            String originalFilename,
            String contentType,
            byte[] bytes
    ) implements MultipartFile {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            Files.write(dest.toPath(), bytes);
        }
    }
}
