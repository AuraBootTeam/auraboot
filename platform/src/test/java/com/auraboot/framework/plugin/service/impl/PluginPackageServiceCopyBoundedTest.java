package com.auraboot.framework.plugin.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decompression-bomb guard test for plugin package extraction
 * ({@link PluginPackageServiceImpl#copyBounded}).
 *
 * <p>Security regression: {@code extractZip} used {@code Files.copy(zis, ...)} with no size
 * bound → a small package could inflate to fill the shared host disk. {@code copyBounded}
 * streams at most {@code max + 1} bytes so the caller can detect and reject an oversized entry.
 */
@DisplayName("PluginPackageServiceImpl copyBounded guard")
class PluginPackageServiceCopyBoundedTest {

    @Test
    @DisplayName("input larger than the cap writes > max (caller then rejects)")
    void oversizedInputExceedsCap(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("out.bin");
        byte[] data = new byte[100];
        long written = PluginPackageServiceImpl.copyBounded(new ByteArrayInputStream(data), target, 50);
        assertTrue(written > 50, "written must exceed the cap so the caller rejects it");
    }

    @Test
    @DisplayName("input within the cap copies fully")
    void withinCapCopiesFully(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("ok.bin");
        byte[] data = new byte[30];
        long written = PluginPackageServiceImpl.copyBounded(new ByteArrayInputStream(data), target, 50);
        assertEquals(30, written);
    }
}
