package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.plugin.extension.FileAccessor;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;

/** Request-context-aware file facade injectable into application modules. */
@Service
public class ApplicationFileAccessorImpl implements FileAccessor {
    private final FileService fileService;
    private final StorageProvider storageProvider;

    public ApplicationFileAccessorImpl(FileService fileService, StorageProvider storageProvider) {
        this.fileService = fileService;
        this.storageProvider = storageProvider;
    }

    private FileAccessorImpl delegate() {
        return new FileAccessorImpl(fileService, storageProvider, MetaContext.getCurrentUserId());
    }

    @Override public InputStream open(String fileId) { return delegate().open(fileId); }
    @Override public FileMetadata describe(String fileId) { return delegate().describe(fileId); }
    @Override public boolean isLinkedTo(String fileId, String entityType, String entityId, String fieldName) {
        return delegate().isLinkedTo(fileId, entityType, entityId, fieldName);
    }
    @Override public boolean retain(String fileId) { return delegate().retain(fileId); }
    @Override public SavedFile save(String originalName, String contentType, byte[] bytes) {
        return delegate().save(originalName, contentType, bytes);
    }
    @Override public SavedFile saveAndLink(String originalName, String contentType, byte[] bytes,
                                           String entityType, String entityId, String fieldName) {
        return delegate().saveAndLink(originalName, contentType, bytes, entityType, entityId, fieldName);
    }
    @Override public List<FileMetadata> listLinked(String entityType, String entityId, String fieldName) {
        List<FileEntity> files = fileService.getFilesByEntityAndField(entityType, entityId, fieldName);
        if (files == null) return List.of();
        return files.stream().map(file -> new FileMetadata(
                file.getPid(), file.getOriginalName(), file.getFileSize() == null ? 0L : file.getFileSize(),
                file.getMimeType(), file.getCreatedBy() == null ? null : file.getCreatedBy().toString(),
                file.getStatus())).toList();
    }
    @Override public boolean delete(String fileId, String actorUserId) {
        Long actor = actorUserId == null ? MetaContext.getCurrentUserId() : Long.valueOf(actorUserId);
        return fileService.deleteFile(fileId, actor);
    }
}
