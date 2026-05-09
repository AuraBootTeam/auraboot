package com.auraboot.framework.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DocumentParserService")
class DocumentParserServiceTest {

    private final DocumentParserService service = new DocumentParserService();

    @Test
    @DisplayName("parse reads markdown verbatim")
    void parseMarkdown(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("a.md");
        Files.writeString(f, "# Title\nbody", StandardCharsets.UTF_8);
        String text = service.parse(f.toString(), "md");
        assertTrue(text.contains("# Title"));
        assertTrue(text.contains("body"));
    }

    @Test
    @DisplayName("parse reads txt verbatim")
    void parseTxt(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("a.txt");
        Files.writeString(f, "plain text content", StandardCharsets.UTF_8);
        assertEquals("plain text content", service.parse(f.toString(), "txt"));
    }

    @Test
    @DisplayName("parse reads csv verbatim")
    void parseCsv(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("a.csv");
        Files.writeString(f, "a,b\n1,2", StandardCharsets.UTF_8);
        assertTrue(service.parse(f.toString(), "csv").contains("a,b"));
    }

    @Test
    @DisplayName("parse handles uppercase docType")
    void parseUppercaseType(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("a.txt");
        Files.writeString(f, "x", StandardCharsets.UTF_8);
        assertEquals("x", service.parse(f.toString(), "TXT"));
    }

    @Test
    @DisplayName("parse strips HTML tags and normalises entities")
    void parseHtml(@TempDir Path tmp) throws IOException {
        String html = "<html><body><p>Hello&nbsp;<b>World</b> &amp; &lt;there&gt;</p></body></html>";
        Path f = tmp.resolve("a.html");
        Files.writeString(f, html, StandardCharsets.UTF_8);
        String text = service.parse(f.toString(), "html");
        assertTrue(text.contains("Hello"));
        assertTrue(text.contains("World"));
        assertTrue(text.contains("&"));
        assertTrue(text.contains("<there>"));
        assertFalse(text.contains("<p>"));
        assertFalse(text.contains("&nbsp;"));
    }

    @Test
    @DisplayName("parse rejects unsupported docType with IllegalArgumentException")
    void parseUnsupported(@TempDir Path tmp) {
        assertThrows(IllegalArgumentException.class,
                () -> service.parse(tmp.resolve("a.bin").toString(), "bin"));
    }

    @Test
    @DisplayName("parse PDF throws IOException for non-PDF input")
    void parsePdfBadFile(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("a.pdf");
        Files.writeString(f, "not a pdf", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> service.parse(f.toString(), "pdf"));
    }

    @Test
    @DisplayName("parse DOCX throws IOException for non-DOCX input")
    void parseDocxBadFile(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("a.docx");
        Files.writeString(f, "not a docx", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> service.parse(f.toString(), "docx"));
    }
}
