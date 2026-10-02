package com.auraboot.framework.branding;

import com.auraboot.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Arrays;

@Service
@RequiredArgsConstructor
public class AuthAppearanceAssetService {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private final Environment environment;
    private final DeploymentBrandingProvider brandingProvider;

    public record Asset(String url) {}

    public Asset upload(MultipartFile file) throws IOException {
        if (!brandingProvider.isCommercialBrandingEnabled()) {
            throw new BusinessException("$i18n:auth.appearance.commercialRequired");
        }
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new BusinessException("$i18n:auth.appearance.assetSize");
        }
        byte[] bytes = file.getBytes();
        String extension = extension(bytes);
        if (extension == null) {
            throw new BusinessException("$i18n:auth.appearance.assetType");
        }
        String name;
        try {
            name = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + extension;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        Path root = root();
        Files.createDirectories(root);
        Path asset = root.resolve(name);
        if (!Files.exists(asset)) {
            // Content-addressed files are immutable and safe to retain for release rollback.
            try {
                Files.write(asset, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
            } catch (java.nio.file.FileAlreadyExistsException collision) {
                // Another upload of the same content won the race; verify it below.
            }
        }
        if (Files.isSymbolicLink(asset) || !asset.toRealPath().getParent().equals(root.toRealPath())) {
            throw new IllegalStateException("Appearance asset must remain inside its configured storage");
        }
        if (!Arrays.equals(Files.readAllBytes(asset), bytes)) {
            throw new IllegalStateException("Appearance asset content does not match its digest");
        }
        return new Asset("/api/auth/appearance/assets/" + name);
    }

    public Path read(String filename) throws IOException {
        if (!filename.matches("[a-f0-9]{64}\\.(png|jpg|webp)")) {
            throw new java.nio.file.NoSuchFileException(filename);
        }
        Path root = root().toRealPath();
        Path file = root.resolve(filename).toRealPath();
        if (!file.getParent().equals(root) || !Files.isRegularFile(file)) {
            throw new java.nio.file.NoSuchFileException(filename);
        }
        return file;
    }

    private Path root() {
        String configured = environment.getProperty("AURABOOT_AUTH_APPEARANCE_ASSET_ROOT", "");
        if (configured.isBlank() || !Path.of(configured).isAbsolute()) {
            throw new BusinessException("$i18n:auth.appearance.assetStorageRequired");
        }
        return Path.of(configured).normalize();
    }

    private static String extension(byte[] bytes) {
        if (bytes.length >= 24 && bytes[0] == (byte) 0x89 && bytes[1] == 'P'
                && bytes[2] == 'N' && bytes[3] == 'G' && bytes[4] == 13
                && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10) {
            return ".png";
        }
        if (bytes.length >= 4 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8
                && bytes[2] == (byte) 0xff && bytes[bytes.length - 2] == (byte) 0xff
                && bytes[bytes.length - 1] == (byte) 0xd9) {
            return ".jpg";
        }
        if (bytes.length >= 20 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E'
                && bytes[10] == 'B' && bytes[11] == 'P') {
            return ".webp";
        }
        return null;
    }
}
