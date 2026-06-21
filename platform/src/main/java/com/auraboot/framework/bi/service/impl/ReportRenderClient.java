package com.auraboot.framework.bi.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Invokes the Node report renderer as a subprocess to produce a real WYSIWYG PDF
 * (Phase 3 Option A', DDR-2026-06-21). Serialises {@code { model, dataSets }} to
 * the renderer's stdin and reads the PDF back from a temp file passed via
 * {@code --out}. This is the JVM↔Node boundary; the renderer reuses the frontend
 * chart logic (single rendering source of truth).
 */
@Component
@Slf4j
public class ReportRenderClient {

    private final ObjectMapper objectMapper;
    private final ReportRenderProperties properties;

    public ReportRenderClient(ObjectMapper objectMapper, ReportRenderProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Render a report to a WYSIWYG PDF via the Node renderer subprocess.
     *
     * @return the PDF bytes, or {@code null} when the renderer is not configured
     *     (the caller then falls back to the legacy PDFBox text export).
     * @throws ReportRenderException when the configured renderer fails.
     */
    public byte[] renderPdf(Map<String, Object> reportDsl,
                            Map<String, List<Map<String, Object>>> dataSets) {
        if (!properties.isEnabled() || properties.getCommand().isEmpty()) {
            return null;
        }

        Path outFile = null;
        Path errFile = null;
        try {
            outFile = Files.createTempFile("auraboot-report-", ".pdf");
            errFile = Files.createTempFile("auraboot-report-", ".log");

            List<String> command = new ArrayList<>(properties.getCommand());
            command.add("--out");
            command.add(outFile.toString());

            ProcessBuilder pb = new ProcessBuilder(command);
            // PDF goes to the --out file; stdout is unused. stderr -> a temp file so
            // a chatty renderer can never fill (and block on) an undrained pipe.
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(errFile.toFile());

            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", reportDsl);
            request.put("dataSets", dataSets == null ? Map.of() : dataSets);

            Process process = pb.start();
            try (OutputStream stdin = process.getOutputStream()) {
                objectMapper.writeValue(stdin, request);
            }

            boolean finished = process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ReportRenderException(
                        "report renderer timed out after " + properties.getTimeoutSeconds() + "s");
            }
            int exit = process.exitValue();
            if (exit != 0) {
                throw new ReportRenderException("report renderer exited " + exit + ": " + tailStderr(errFile));
            }

            byte[] pdf = Files.readAllBytes(outFile);
            if (!isPdf(pdf)) {
                throw new ReportRenderException(
                        "report renderer did not produce a PDF (" + pdf.length + " bytes): " + tailStderr(errFile));
            }
            return pdf;
        } catch (IOException e) {
            throw new ReportRenderException("report renderer I/O failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReportRenderException("report renderer interrupted", e);
        } finally {
            deleteQuietly(outFile);
            deleteQuietly(errFile);
        }
    }

    private static boolean isPdf(byte[] bytes) {
        return bytes.length >= 5
                && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-';
    }

    private static String tailStderr(Path errFile) {
        try {
            String s = Files.readString(errFile, StandardCharsets.UTF_8).trim();
            return s.length() > 500 ? "..." + s.substring(s.length() - 500) : s;
        } catch (IOException e) {
            return "<stderr unavailable>";
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }
}
