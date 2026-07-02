package com.auraboot.framework.user.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decompression-bomb guard test for the employee workbook parser.
 *
 * <p>Security regression: {@code readZipEntries} used {@code readAllBytes()} per entry with
 * no size cap — a small (highly compressible) xlsx could inflate to GBs and OOM the shared
 * JVM. Per-entry / count / total limits now bound uncompressed size.
 */
@DisplayName("EmployeeAccountWorkbookParser zip-bomb guard")
class EmployeeAccountWorkbookParserZipBombTest {

    private final EmployeeAccountWorkbookParser parser = new EmployeeAccountWorkbookParser();

    private byte[] zipWithEntry(String name, long uncompressedBytes) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(name));
            byte[] chunk = new byte[1024 * 1024]; // zeros — compress to almost nothing
            long remaining = uncompressedBytes;
            while (remaining > 0) {
                int n = (int) Math.min(chunk.length, remaining);
                zos.write(chunk, 0, n);
                remaining -= n;
            }
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    @Test
    @DisplayName("an entry inflating beyond the per-entry cap is rejected (no OOM)")
    void oversizedEntryRejected() throws Exception {
        // 11 MB uncompressed > 10 MB cap, but compresses to a tiny upload (zip bomb shape).
        byte[] bomb = zipWithEntry("xl/worksheets/sheet1.xml", 11L * 1024 * 1024);
        assertTrue(bomb.length < 100 * 1024, "compressed bomb should be tiny");
        assertThrows(IllegalArgumentException.class,
                () -> parser.readZipEntries(new ByteArrayInputStream(bomb)));
    }

    @Test
    @DisplayName("a normal small workbook is read back")
    void normalWorkbookRead() throws Exception {
        byte[] ok = zipWithEntry("xl/worksheets/sheet1.xml", 1024);
        Map<String, byte[]> entries = parser.readZipEntries(new ByteArrayInputStream(ok));
        assertEquals(1024, entries.get("xl/worksheets/sheet1.xml").length);
    }
}
