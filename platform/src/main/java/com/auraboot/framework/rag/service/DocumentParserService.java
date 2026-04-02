package com.auraboot.framework.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extracts plain text from various document formats.
 * Uses PDFBox (transitive dep via openhtmltopdf) for PDF, Apache POI for DOCX.
 */
@Slf4j
@Service
public class DocumentParserService {

    /**
     * Extract text content from a file based on its type.
     *
     * @param filePath absolute path to the file
     * @param docType  one of: pdf, docx, md, txt, csv, html
     * @return extracted plain text
     */
    public String parse(String filePath, String docType) throws IOException {
        return switch (docType.toLowerCase()) {
            case "pdf" -> parsePdf(filePath);
            case "docx" -> parseDocx(filePath);
            case "md", "txt", "csv" -> Files.readString(Path.of(filePath));
            case "html" -> parseHtml(Files.readString(Path.of(filePath)));
            default -> throw new IllegalArgumentException("Unsupported document type: " + docType);
        };
    }

    private String parsePdf(String filePath) throws IOException {
        try (PDDocument doc = PDDocument.load(new java.io.File(filePath))) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    private String parseDocx(String filePath) throws IOException {
        try (InputStream is = new FileInputStream(filePath);
             XWPFDocument doc = new XWPFDocument(is)) {

            StringBuilder sb = new StringBuilder();
            for (var element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph para) {
                    String text = para.getText();
                    if (text != null && !text.isBlank()) {
                        sb.append(text).append('\n');
                    }
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        for (XWPFTableCell cell : row.getTableCells()) {
                            sb.append(cell.getText()).append('\t');
                        }
                        sb.append('\n');
                    }
                }
            }
            return sb.toString();
        }
    }

    /**
     * Strip HTML tags to extract plain text.
     */
    private String parseHtml(String html) {
        // Simple tag stripping — Jsoup is available but this is sufficient for richtext content
        return html.replaceAll("<[^>]+>", " ")
                    .replaceAll("&nbsp;", " ")
                    .replaceAll("&amp;", "&")
                    .replaceAll("&lt;", "<")
                    .replaceAll("&gt;", ">")
                    .replaceAll("\\s+", " ")
                    .trim();
    }
}
