package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** Private storage and checksum verification; temporary verification files never become public object URLs. */
@Component
@RequiredArgsConstructor
public class ExportArtifactStore {
    private final StorageProvider storage;
    public record Stored(String key,long size,String checksum) {}
    public record Download(InputStream content,long size) {}

    public Stored upload(Path source,String key) throws IOException {
        if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Export source must be a regular temporary file");
        long size=Files.size(source);var digest=digest();
        try(var content=Files.newInputStream(source)) {hash(content,digest,null,size);}
        try(var content=Files.newInputStream(source)) {storage.upload(key,content,size,"text/csv; charset=UTF-8");}
        return new Stored(key,size,HexFormat.of().formatHex(digest.digest()));
    }

    /** Verify the full private object before returning any bytes to the HTTP response. */
    public Download verified(String key,long size,String checksum) throws IOException {
        if(size<0 || checksum==null || !checksum.matches("[0-9a-f]{64}") || !storage.exists(key))
            throw BusinessException.i18n("export.artifact_unavailable");
        Path verified=Files.createTempFile("verified-export-",".csv");boolean ready=false;
        try {
            var digest=digest();
            try(var source=storage.download(key);var target=Files.newOutputStream(verified)) {
                hash(source,digest,target,size);
            }
            if(!MessageDigest.isEqual(HexFormat.of().parseHex(checksum),digest.digest()))
                throw BusinessException.i18n("export.artifact_integrity");
            var content=new FilterInputStream(Files.newInputStream(verified)) {
                @Override public void close() throws IOException {
                    try {super.close();} finally {Files.deleteIfExists(verified);}
                }
            };
            ready=true;return new Download(content,size);
        } finally {if(!ready) Files.deleteIfExists(verified);}
    }
    private static void hash(InputStream input,MessageDigest digest,OutputStream output,long expected) throws IOException {
        byte[] buffer=new byte[8192];long size=0;int count;
        while((count=input.read(buffer))!=-1) {
            size+=count;
            if(size>expected) throw BusinessException.i18n("export.artifact_integrity");
            digest.update(buffer,0,count);if(output!=null) output.write(buffer,0,count);
        }
        if(size!=expected) throw BusinessException.i18n("export.artifact_integrity");
    }
    private static MessageDigest digest() {
        try {return MessageDigest.getInstance("SHA-256");}
        // CATCH: a required JRE algorithm is unavailable; no transactional work occurs here.
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException("SHA-256 is required",impossible);}
    }
    public void delete(String key){storage.delete(key);}
}
