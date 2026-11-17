package com.auraboot.framework.application.database.mybatis;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MybatisCarrierIntegrityTest {
    @TempDir Path directory;

    @Test void currentRuntimeUsesOneCoLocatedCompatibleCarrier() {
        assertDoesNotThrow(() -> MybatisCarrierIntegrity.verify(getClass().getClassLoader()));
    }

    @Test void missingCarrierFailsClosed() throws Exception {
        try (var empty = new URLClassLoader(new URL[0], null)) {
            assertThrows(IllegalStateException.class, () -> MybatisCarrierIntegrity.verify(empty));
        }
    }

    @Test void applicationClassOverrideCannotSilentlyHideTheEmbeddedDependency() throws Exception {
        Path duplicate = directory.resolve("org/apache/ibatis/ognl/Ognl.class");
        Files.createDirectories(duplicate.getParent());
        Files.write(duplicate, new byte[] {0}); // Resource enumeration rejects it before class loading.
        try (var split = new URLClassLoader(new URL[] {directory.toUri().toURL()}, getClass().getClassLoader())) {
            var failure = assertThrows(IllegalStateException.class, () -> MybatisCarrierIntegrity.verify(split));
            assertTrue(failure.getMessage().contains("found 2"));
        }
    }
}
