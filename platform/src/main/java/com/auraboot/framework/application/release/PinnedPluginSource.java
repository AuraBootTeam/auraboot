package com.auraboot.framework.application.release;

import com.auraboot.framework.plugin.source.PluginSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable bytes verified using the staged-directory digest defined by application-contract.mjs. */
public final class PinnedPluginSource implements PluginSource {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int MAX_ENTRIES = 10000;
    private final Map<String, byte[]> files = new HashMap<>();
    private final Set<String> directories = new HashSet<>();
    private final String digest;
    private long byteCount;
    private int entryCount;

    private PinnedPluginSource(Path root, String expectedDigest) throws IOException {
        if (expectedDigest == null || !expectedDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Externally pinned definition digest required");
        }
        var entries = new ArrayList<String>();
        capture(root, "", entries);
        digest = hash((String.join("\n", entries) + "\n").getBytes(StandardCharsets.UTF_8));
        if (!digest.equals(expectedDigest)) throw new IllegalArgumentException("Definition artifact digest mismatch");
    }

    public static PinnedPluginSource capture(Path root, String expectedDigest) throws IOException {
        if (root == null) throw new IllegalArgumentException("Definition directory required");
        return new PinnedPluginSource(root, expectedDigest);
    }

    private void capture(Path directory, String prefix, List<String> entries) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Definition source must be a non-symlink directory");
        if (++entryCount > MAX_ENTRIES) throw new IOException("Definition artifact entry limit exceeded");
        directories.add(prefix);
        try (var children = Files.list(directory)) {
            for (var child : children.sorted(java.util.Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                String name = child.getFileName().toString();
                if (name.chars().anyMatch(c -> c < 32) || name.contains("\\")) throw new IOException("Unsupported definition artifact path");
                String relative = prefix.isEmpty() ? name : prefix + "/" + name;
                if (Files.isSymbolicLink(child)) throw new IOException("Definition symlinks are not permitted");
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) capture(child, relative, entries);
                else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    if (++entryCount > MAX_ENTRIES) throw new IOException("Definition artifact entry limit exceeded");
                    byte[] bytes;
                    try (var stream = Files.newInputStream(child, LinkOption.NOFOLLOW_LINKS)) {
                        bytes = stream.readNBytes((int) (MAX_BYTES - byteCount + 1));
                    }
                    byteCount += bytes.length;
                    if (byteCount > MAX_BYTES) throw new IOException("Definition artifact byte limit exceeded");
                    files.put(relative, bytes);
                    entries.add(relative + "\0" + hash(bytes));
                } else throw new IOException("Unsupported definition artifact entry");
            }
        }
    }

    private static String hash(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }
    private static String checked(String relative) {
        if (relative == null || relative.startsWith("/") || relative.contains("\\")
                || java.util.Arrays.stream(relative.split("/", -1)).anyMatch(part -> part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException("Canonical relative resource path required");
        }
        return relative;
    }
    @Override public boolean requiresCompleteResources() { return true; }
    public String digest() { return digest; }
    @Override public String getSourceId() { return "pinned:" + digest; }
    @Override public boolean exists(String relative) { String key = checked(relative); return files.containsKey(key) || directories.contains(key); }
    @Override public InputStream readResource(String relative) throws IOException {
        byte[] bytes = files.get(checked(relative));
        if (bytes == null) throw new IOException("Definition resource missing: " + relative);
        return new ByteArrayInputStream(bytes);
    }
    @Override public String readString(String relative) throws IOException {
        try (var stream = readResource(relative)) { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
    }
    @Override public List<String> listFiles(String relativeDir, String extension) {
        String directory = checked(relativeDir);
        String prefix = directory.isEmpty() ? "" : directory + "/";
        return files.keySet().stream().filter(key -> key.startsWith(prefix) && !key.substring(prefix.length()).contains("/"))
                .filter(key -> extension == null || key.endsWith(extension)).sorted().toList();
    }
}
