package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PageSchemaImportGateBoundaryTest {
    @Test
    void absentEmptyAndNullPageEntriesFollowTheDeclaredGateContract() {
        var gate = gate();
        assertDoesNotThrow(() -> gate.enforce(null));
        var manifest = new PluginManifestExtended(); manifest.setPages(List.of());
        assertDoesNotThrow(() -> gate.enforce(manifest));
        manifest.setPages(Arrays.asList(null, validPage()));
        assertDoesNotThrow(() -> gate.enforce(manifest));
        assertDoesNotThrow(() -> gate.enforce(manifest));
    }

    @Test
    void missingAndBlankPageKeysRemainTraceableInSchemaAndStructureErrors() {
        for (String key : Arrays.asList(null, " ")) {
            var page = validPage(); page.setPageKey(key); page.setLayout(Map.of("type", "invalid"));
            page.setBlocks(List.of(Map.of("blockType", "table")));
            var failure = assertThrows(PageSchemaImportException.class, () -> gate().enforce(manifest(page)));
            assertTrue(failure.getMessage().contains("<unknown>"));
            assertTrue(failure.getMessage().contains("S-PAGE-JSON-SCHEMA"));
            assertTrue(failure.getMessage().contains("S-PAGE-BLOCK-STRUCTURE"));
        }
    }

    @Test
    void missingPackagedSchemaIsAnExplicitFailure() throws Exception {
        assertSchemaResourceFailure(false, "Missing classpath resource");
    }

    @Test
    void unreadablePackagedSchemaIsAnExplicitFailure() throws Exception {
        assertSchemaResourceFailure(true, "Unreadable schema fixture");
    }

    private void assertSchemaResourceFailure(boolean unreadable, String expected) throws Exception {
        String gateName = PageSchemaImportGate.class.getName();
        byte[] bytecode;
        try (var in = PageSchemaImportGate.class.getResourceAsStream("PageSchemaImportGate.class")) {
            assertNotNull(in); bytecode = in.readAllBytes();
        }
        // Load the actual production gate in a resource-isolated classloader.
        // All dependencies are unchanged; only the packaged schema is faulted.
        ClassLoader loader = new ClassLoader(PageSchemaImportGate.class.getClassLoader()) {
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!gateName.equals(name)) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = defineClass(name, bytecode, 0, bytecode.length);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
            public InputStream getResourceAsStream(String name) {
                if (!"schemas/page-import-v4.schema.json".equals(name)) return super.getResourceAsStream(name);
                if (!unreadable) return null;
                return new InputStream() { public int read() throws IOException { throw new IOException("Unreadable schema fixture"); } };
            }
        };
        Class<?> isolated = loader.loadClass(gateName);
        Object gate = isolated.getConstructor(PageSchemaValidator.class, ObjectMapper.class)
            .newInstance(new PageSchemaValidator(), new ObjectMapper());
        var failure = assertThrows(InvocationTargetException.class, () -> isolated.getMethod("enforce", PluginManifestExtended.class).invoke(gate, manifest(validPage())));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains(expected));
    }
    private PageSchemaImportGate gate() { return new PageSchemaImportGate(new PageSchemaValidator(), new ObjectMapper()); }
    private PluginManifestExtended manifest(PageSchemaDTO page) { var manifest = new PluginManifestExtended(); manifest.setPages(List.of(page)); return manifest; }
    private PageSchemaDTO validPage() {
        var page = new PageSchemaDTO(); page.setPageKey("test_page"); page.setKind("list"); page.setSchemaVersion(4);
        page.setLayout(Map.of("type", "stack")); page.setBlocks(List.of(Map.of("id", "table", "blockType", "table", "columns", List.of(Map.of("field", "name", "label", "Name")))));
        return page;
    }
}
