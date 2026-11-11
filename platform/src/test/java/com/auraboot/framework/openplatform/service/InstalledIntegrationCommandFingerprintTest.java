package com.auraboot.framework.openplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure fingerprint algorithm checks; no persistence or delivery claim. */
class InstalledIntegrationCommandFingerprintTest {
    private final ObjectMapper json = new ObjectMapper();

    private String config(String... installations) throws Exception {
        var root = json.createObjectNode();
        root.put("handler", "fixed-handler");
        root.putArray("permissions").add("business.write");
        root.put("description", "中文\\\" bindings: [ not an array ]");
        var declaration = root.putObject("handlerParams").putObject("installedIntegration");
        declaration.put("sourceCode", "source");
        declaration.put("capability", "capability");
        ArrayNode bindings = declaration.putArray("bindings");
        for (String installation : installations) bindings.addObject().put("installationPid", installation)
                .put("revision", 1).putObject("parameters").put("connectionPid", installation + "-connection");
        return json.writeValueAsString(root);
    }

    private Map<String, String> commands(String config) {
        var result = new LinkedHashMap<String, String>();
        for (String command : List.of("accept", "process", "result")) result.put(command, config);
        return result;
    }

    private String digest(Map<String, String> configs) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsBytes(configs)));
        } catch (java.security.NoSuchAlgorithmException | java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private boolean legacy(Map<String, String> current, Map<String, String> original, String installation) {
        return InstalledIntegrationCommandFingerprint.legacyPrefixMatches(json, current, installation,
                digest(current), digest(original), this::digest);
    }

    @Test void scopedAuthorityIgnoresOtherBindingsButPinsTheSelectedBindingAndCommand() throws Exception {
        var original = InstalledIntegrationCommandFingerprint.scoped(json, commands(config("own")), "own");
        assertEquals(original, InstalledIntegrationCommandFingerprint.scoped(json,
                commands(config("other-before", "own", "other-after")), "own"));
        var changed = (ObjectNode) json.readTree(config("own", "other"));
        var selected = (ObjectNode) changed.at("/handlerParams/installedIntegration/bindings/0");
        selected.put("revision", 2);
        assertNotEquals(original, InstalledIntegrationCommandFingerprint.scoped(json,
                commands(changed.toString()), "own"));
        changed = (ObjectNode) json.readTree(config("own"));
        changed.put("handler", "substituted");
        assertNotEquals(original, InstalledIntegrationCommandFingerprint.scoped(json,
                commands(changed.toString()), "own"));
        changed = (ObjectNode) json.readTree(config("own"));
        changed.putArray("permissions").add("different.permission");
        assertNotEquals(original, InstalledIntegrationCommandFingerprint.scoped(json,
                commands(changed.toString()), "own"));
    }

    @Test void legacySuffixProjectionPreservesCompactSpacedAndPrettyJsonExactly() throws Exception {
        for (int format = 0; format < 3; format++) {
            String old = config("before", "own");
            String current = config("before", "own", "appended", "another-appended");
            if (format == 1) {
                old = old.replace(":", ": ").replace(",", ", ");
                current = current.replace(":", ": ").replace(",", ", ");
            } else if (format == 2) {
                old = json.writerWithDefaultPrettyPrinter().writeValueAsString(json.readTree(old));
                current = json.writerWithDefaultPrettyPrinter().writeValueAsString(json.readTree(current));
            }
            assertTrue(legacy(commands(current), commands(old), "own"), "format " + format);
        }
    }

    @Test void legacyCannotRemoveTheSelectedInstallationOrReorderEarlierBindings() throws Exception {
        assertFalse(legacy(commands(config("before", "own", "after")), commands(config("before")), "own"));
        assertFalse(legacy(commands(config("own", "before", "after")), commands(config("before", "own")), "own"));
    }

    @Test void legacyCannotAcceptChangedEarlierAuthorityOrDifferentCommandBytes() throws Exception {
        var original = commands(config("own"));
        String current = config("own", "after");
        assertFalse(legacy(commands(current.replace("fixed-handler", "substituted")), original, "own"));
        assertFalse(legacy(commands(current.replace("own-connection", "another-connection")), original, "own"));
        assertFalse(legacy(commands(current.replace("business.write", "business.admin")), original, "own"));
        var inconsistent = commands(current);
        inconsistent.put("result", config("own", "different-after"));
        assertFalse(legacy(inconsistent, original, "own"));
    }

    @Test void missingDuplicateAndMalformedSelectedBindingsAreRejected() throws Exception {
        for (String value : List.of(config("other"), config("own", "own"), config())) {
            assertThrows(IllegalArgumentException.class,
                    () -> InstalledIntegrationCommandFingerprint.scoped(json, commands(value), "own"));
        }
        assertThrows(IllegalArgumentException.class, () -> InstalledIntegrationCommandFingerprint.scoped(json,
                commands(config("own") + " {}"), "own"));
    }

    @Test void lookupConfigurationRemainsPinnedAndHasNoInlineLegacyAncestry() throws Exception {
        var root = (ObjectNode) json.readTree(config("own"));
        var declaration = (ObjectNode) root.at("/handlerParams/installedIntegration");
        declaration.remove("bindings");
        declaration.putObject("bindingLookup").put("reader", "owned-reader");
        var original = InstalledIntegrationCommandFingerprint.scoped(json, commands(root.toString()), "own");
        ((ObjectNode) declaration.get("bindingLookup")).put("reader", "another-reader");
        assertNotEquals(original, InstalledIntegrationCommandFingerprint.scoped(json, commands(root.toString()), "own"));
        assertFalse(legacy(commands(root.toString()), commands(config("own")), "own"));
    }

    @Test void arbitraryOrInvalidOriginalDigestIsNeverAnAuthority() throws Exception {
        var current = commands(config("own", "other"));
        for (String original : List.of("changed", "a".repeat(64))) {
            assertFalse(InstalledIntegrationCommandFingerprint.legacyPrefixMatches(json, current, "own",
                    digest(current), original, this::digest));
        }
    }
}
