package com.auraboot.framework.application.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApplicationDefinitionBundleTest {
    @TempDir
    Path root;

    @Test
    void loadsOnlyTheConfigArtifactPinnedByTheRegisteredApplicationLock() throws Exception {
        Path componentRoot = root.resolve("config/edu-core");
        Files.createDirectories(componentRoot);
        Files.writeString(componentRoot.resolve("plugin.json"), """
                {"pluginId":"com.auraboot.edu","namespace":"edu","version":"1.2.3",
                 "commands":[{"code":"edu:enroll","displayName":"Enroll"}]}
                """);
        String digest = directoryDigest(componentRoot);
        String lockIdentity = writeLock("config/edu-core", digest);

        var bundle = new ApplicationDefinitionBundle(root, root.resolve("empty-retained-store"), new ObjectMapper());
        var manifest = bundle.load("aura-edu", lockIdentity, component("edu-core", digest));
        var repeated = bundle.load("aura-edu", lockIdentity, component("edu-core", digest));

        assertEquals("com.auraboot.edu", manifest.getPluginId());
        assertEquals("edu", manifest.getNamespace());
        assertEquals("edu:enroll", manifest.getCommands().getFirst().getCode());
        assertEquals(manifest, repeated);
        assertEquals(1, bundle.cacheMissCount(), "first exact bundle read loads immutable bytes once");
        assertEquals(1, bundle.cacheHitCount(), "repeated exact bundle read is served from cache");
    }

    @Test
    void rejectsChangedBytesAndPathsOutsideTheApplicationBundle() throws Exception {
        Path componentRoot = root.resolve("config/edu-core");
        Files.createDirectories(componentRoot);
        Files.writeString(componentRoot.resolve("plugin.json"),
                "{\"pluginId\":\"com.auraboot.edu\",\"namespace\":\"edu\",\"version\":\"1.2.3\"}");
        String digest = directoryDigest(componentRoot);
        String lockIdentity = writeLock("config/edu-core", digest);
        Files.writeString(componentRoot.resolve("plugin.json"),
                "{\"pluginId\":\"changed\",\"namespace\":\"edu\",\"version\":\"1.2.3\"}");

        assertThrows(IllegalArgumentException.class, () -> new ApplicationDefinitionBundle(root, new ObjectMapper())
                .load("aura-edu", lockIdentity, component("edu-core", digest)));

        String escapedIdentity = writeLock("../outside", digest);
        assertThrows(IllegalArgumentException.class, () -> new ApplicationDefinitionBundle(root, new ObjectMapper())
                .load("aura-edu", escapedIdentity, component("edu-core", digest)));
    }

    @Test
    void rejectsAPluginVersionThatDiffersFromTheRegisteredComponent() throws Exception {
        Path componentRoot = root.resolve("config/edu-core");
        Files.createDirectories(componentRoot);
        Files.writeString(componentRoot.resolve("plugin.json"),
                "{\"pluginId\":\"com.auraboot.edu\",\"namespace\":\"edu\",\"version\":\"2.0.0\"}");
        String digest = directoryDigest(componentRoot);
        String lockIdentity = writeLock("config/edu-core", digest);

        assertThrows(IllegalArgumentException.class, () -> new ApplicationDefinitionBundle(root, new ObjectMapper())
                .load("aura-edu", lockIdentity, component("edu-core", digest)));
    }

    @Test
    void resolvesAnOlderReleaseFromTheContentAddressedDefinitionStore() throws Exception {
        Path current = root.resolve("current");
        Path retained = root.resolve("retained");
        Path stagedOld = root.resolve("staged-old");
        Files.createDirectories(current.resolve("config/edu-core"));
        Files.createDirectories(stagedOld.resolve("config/edu-core"));
        Files.writeString(current.resolve("config/edu-core/plugin.json"),
                "{\"pluginId\":\"com.auraboot.edu\",\"namespace\":\"edu\",\"version\":\"2.0.0\"}");
        String currentDigest = directoryDigest(current.resolve("config/edu-core"));
        writeLock(current, "config/edu-core", currentDigest);
        Files.writeString(stagedOld.resolve("config/edu-core/plugin.json"),
                "{\"pluginId\":\"com.auraboot.edu\",\"namespace\":\"edu\",\"version\":\"1.2.3\"}");
        String oldDigest = directoryDigest(stagedOld.resolve("config/edu-core"));
        String oldIdentity = writeLock(stagedOld, "config/edu-core", oldDigest);
        Path retainedOld = retained.resolve(oldIdentity.substring("sha256:".length()));
        Files.createDirectories(retained);
        Files.move(stagedOld, retainedOld, StandardCopyOption.ATOMIC_MOVE);

        var manifest = new ApplicationDefinitionBundle(current, retained, new ObjectMapper())
                .load("aura-edu", oldIdentity, component("edu-core", oldDigest));

        assertEquals("1.2.3", manifest.getVersion());
    }

    @Test
    void rejectsASymlinkAtAContentAddressedStoreEntry() throws Exception {
        Path current = root.resolve("current");
        Path retained = root.resolve("retained");
        Files.createDirectories(current.resolve("config/edu-core"));
        Files.createDirectories(retained);
        Files.writeString(current.resolve("config/edu-core/plugin.json"),
                "{\"pluginId\":\"com.auraboot.edu\",\"namespace\":\"edu\",\"version\":\"1.2.3\"}");
        String digest = directoryDigest(current.resolve("config/edu-core"));
        String identity = writeLock(current, "config/edu-core", digest);
        Files.createSymbolicLink(retained.resolve(identity.substring("sha256:".length())), current);

        assertThrows(IllegalArgumentException.class, () ->
                new ApplicationDefinitionBundle(current, retained, new ObjectMapper())
                        .load("aura-edu", identity, component("edu-core", digest)));
    }

    private String writeLock(String localPath, String digest) throws Exception {
        return writeLock(root, localPath, digest);
    }

    private static String writeLock(Path bundleRoot, String localPath, String digest) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode lock = (ObjectNode) mapper.readTree("""
                {"schemaVersion":1,"application":{"id":"aura-edu"},
                 "artifacts":[{"type":"config","id":"edu-core","digest":"%s","localPath":"%s"}]}
                """.formatted(digest, localPath));
        String identity = hash(mapper.writeValueAsBytes(canonical(mapper, lock)));
        lock.put("identity", identity);
        mapper.writeValue(bundleRoot.resolve("application.lock").toFile(), lock);
        return identity;
    }

    private static ApplicationDefinitionMapper.ComponentRow component(String key, String digest) {
        var row = new ApplicationDefinitionMapper.ComponentRow();
        row.componentKey = key;
        row.componentVersion = "1.2.3";
        row.componentDigest = digest;
        return row;
    }

    private static String directoryDigest(Path directory) throws Exception {
        var entries = new ArrayList<String>();
        try (var files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> directory.relativize(path).toString())).toList()) {
                String relative = directory.relativize(file).toString().replace('\\', '/');
                entries.add(relative + "\0" + hash(Files.readAllBytes(file)));
            }
        }
        return hash((String.join("\n", entries) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static JsonNode canonical(ObjectMapper mapper, JsonNode value) {
        if (value.isObject()) {
            ObjectNode object = mapper.createObjectNode();
            var fields = new TreeMap<String, JsonNode>();
            value.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, child) -> object.set(key, canonical(mapper, child)));
            return object;
        }
        if (value.isArray()) {
            ArrayNode array = mapper.createArrayNode();
            value.forEach(child -> array.add(canonical(mapper, child)));
            return array;
        }
        return value;
    }
}
