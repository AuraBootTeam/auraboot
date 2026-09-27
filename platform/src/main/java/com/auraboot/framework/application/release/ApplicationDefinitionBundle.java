package com.auraboot.framework.application.release;

import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.service.impl.PluginDirectoryLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Loads definition bytes only from the immutable application bundle selected by a release. */
@Service
public final class ApplicationDefinitionBundle {
    private final Path root;
    private final Path definitionStore;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, PluginManifestExtended> cache = new ConcurrentHashMap<>();

    public ApplicationDefinitionBundle(Environment environment, ObjectMapper mapper) {
        this(Path.of(environment.getProperty("aura.application.bundle-root", "/opt/auraboot")),
                optionalPath(environment.getProperty("aura.application.definition-store")), mapper);
    }

    ApplicationDefinitionBundle(Path root, ObjectMapper mapper) {
        this(root, null, mapper);
    }

    ApplicationDefinitionBundle(Path root, Path definitionStore, ObjectMapper mapper) {
        this.root = root.toAbsolutePath().normalize();
        this.definitionStore = definitionStore == null ? null : definitionStore.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    public PluginManifestExtended load(String applicationCode, String lockIdentity,
                                       ApplicationDefinitionMapper.ComponentRow component) {
        require(applicationCode != null && applicationCode.matches("[a-z][a-z0-9-]{1,99}"),
                "Invalid application code");
        require(lockIdentity != null && lockIdentity.matches("sha256:[0-9a-f]{64}"),
                "Exact application lock identity required");
        require(component != null && component.componentKey != null
                        && component.componentVersion != null && !component.componentVersion.isBlank()
                        && component.componentDigest != null
                        && component.componentDigest.matches("sha256:[0-9a-f]{64}"),
                "Exact definition component required");
        String cacheKey = lockIdentity + ":" + component.componentKey + ":"
                + component.componentVersion + ":" + component.componentDigest;
        return cache.computeIfAbsent(cacheKey, ignored -> loadUncached(applicationCode, lockIdentity, component));
    }

    private PluginManifestExtended loadUncached(String applicationCode, String lockIdentity,
                                                ApplicationDefinitionMapper.ComponentRow component) {
        try {
            Path bundleRoot = bundleRoot(lockIdentity);
            Path lockPath = inside(bundleRoot, bundleRoot.resolve("application.lock"));
            JsonNode lock = mapper.readTree(Files.readAllBytes(lockPath));
            require(lock.path("schemaVersion").asInt() == 1, "Application lock schemaVersion must be one");
            require(applicationCode.equals(lock.path("application").path("id").asText()),
                    "Application lock belongs to a different application");
            require(lock.path("identity").asText().equals(computedLockIdentity(lock)),
                    "Application lock identity is invalid");
            require(lockIdentity.equals(lock.path("identity").asText()),
                    "Application lock identity differs from the registered release");
            List<JsonNode> matches = new ArrayList<>();
            for (JsonNode artifact : lock.path("artifacts")) {
                if ("config".equals(artifact.path("type").asText())
                        && component.componentKey.equals(artifact.path("id").asText())
                        && component.componentDigest.equals(artifact.path("digest").asText())) {
                    matches.add(artifact);
                }
            }
            require(matches.size() == 1, "Definition component is not uniquely pinned by the application lock");
            String localPath = matches.getFirst().path("localPath").asText();
            require(!localPath.isBlank(), "Definition component localPath is required");
            Path componentRoot = inside(bundleRoot, bundleRoot.resolve(localPath));
            PinnedPluginSource source = PinnedPluginSource.capture(componentRoot, component.componentDigest);
            PluginManifestExtended manifest = new PluginDirectoryLoader().loadFromSource(source);
            require(manifest.getPluginId() != null && !manifest.getPluginId().isBlank()
                            && manifest.getNamespace() != null && !manifest.getNamespace().isBlank(),
                    "Definition component requires pluginId and namespace");
            require(component.componentVersion.equals(manifest.getVersion()),
                    "Definition component version differs from the registered release");
            return manifest;
        } catch (IOException failure) {
            throw new IllegalStateException("Application definition bundle cannot be read", failure);
        }
    }

    private Path bundleRoot(String lockIdentity) throws IOException {
        if (definitionStore != null) {
            Path candidate = definitionStore.resolve(lockIdentity.substring("sha256:".length())).normalize();
            require(candidate.startsWith(definitionStore), "Definition lock identity escapes the store");
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS),
                        "Retained definition bundle must be a non-symlink directory");
                return inside(definitionStore, candidate);
            }
        }
        return root;
    }

    private String computedLockIdentity(JsonNode lock) {
        require(lock.isObject(), "Application lock must be an object");
        ObjectNode content = ((ObjectNode) lock).deepCopy();
        content.remove("identity");
        try {
            byte[] bytes = mapper.writeValueAsBytes(canonical(content));
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Application lock identity cannot be computed", failure);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode object = mapper.createObjectNode();
            var fields = new TreeMap<String, JsonNode>();
            value.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, child) -> object.set(key, canonical(child)));
            return object;
        }
        if (value.isArray()) {
            ArrayNode array = mapper.createArrayNode();
            value.forEach(child -> array.add(canonical(child)));
            return array;
        }
        return value;
    }

    private Path inside(Path boundary, Path candidate) throws IOException {
        Path normalizedBoundary = boundary.toAbsolutePath().normalize();
        Path normalized = candidate.toAbsolutePath().normalize();
        require(normalized.startsWith(normalizedBoundary), "Definition artifact path escapes the application bundle");
        Path realRoot = normalizedBoundary.toRealPath();
        Path real = normalized.toRealPath();
        require(real.startsWith(realRoot), "Definition artifact resolves outside the application bundle");
        return real;
    }

    private static Path optionalPath(String value) {
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
