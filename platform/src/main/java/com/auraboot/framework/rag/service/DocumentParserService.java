package com.auraboot.framework.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Extracts plain text from knowledge-base source documents.
 *
 * <p>Parsers read from an {@link InputStream}, never from a filesystem path: the file may live in
 * MinIO / S3 / OSS (see {@code aura.storage.type}), where the {@code local_path} recorded on the
 * file entity is a remote object key, not something {@code FileInputStream} can open. Callers
 * obtain the stream from {@code StorageProvider.download(key)}.
 *
 * <p>Legacy binary formats ({@code .ppt}, {@code .xls}, {@code .doc}) are <b>not</b> supported —
 * they need {@code poi-scratchpad}, which is not on the classpath. Only OOXML is accepted.
 */
@Slf4j
@Service
public class DocumentParserService {

    /** Doc types this parser can handle. Must stay in sync with the {@code chk_doc_type} CHECK constraint. */
    public static final Set<String> SUPPORTED_DOC_TYPES =
            Set.of("pdf", "docx", "pptx", "xlsx", "md", "txt", "csv", "html");

    /**
     * Extract text content from a document stream.
     *
     * @param content raw document bytes; closed by the caller
     * @param docType one of {@link #SUPPORTED_DOC_TYPES}
     * @return extracted plain text
     */
    public String parse(InputStream content, String docType) throws IOException {
        return switch (docType.toLowerCase()) {
            case "pdf" -> parsePdf(content);
            case "docx" -> parseDocx(content);
            case "pptx" -> parsePptx(content);
            case "xlsx" -> parseXlsx(content);
            case "md", "txt", "csv" -> new String(content.readAllBytes(), StandardCharsets.UTF_8);
            case "html" -> parseHtml(new String(content.readAllBytes(), StandardCharsets.UTF_8));
            default -> throw new IllegalArgumentException("Unsupported document type: " + docType);
        };
    }

    private String parsePdf(InputStream content) throws IOException {
        try (PDDocument doc = PDDocument.load(content)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private String parseDocx(InputStream content) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(content)) {
            StringBuilder sb = new StringBuilder();
            for (var element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph para) {
                    appendLine(sb, para.getText());
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
        } catch (NotOfficeXmlFileException | POIXMLException e) {
            throw new IOException("Failed to parse DOCX", e);
        }
    }

    /**
     * Flatten a presentation to text, slide by slide. Speaker notes are included: they routinely
     * carry the substance a slide only gestures at, and dropping them loses recallable content.
     */
    private String parsePptx(InputStream content) throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow(content)) {
            StringBuilder sb = new StringBuilder();
            int slideNo = 0;
            for (XSLFSlide slide : ppt.getSlides()) {
                slideNo++;
                sb.append("# Slide ").append(slideNo);
                String title = slide.getTitle();
                if (title != null && !title.isBlank()) {
                    sb.append(": ").append(title.strip());
                }
                sb.append('\n');

                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        appendLine(sb, textShape.getText());
                    }
                }

                XSLFNotes notes = slide.getNotes();
                if (notes != null) {
                    StringBuilder notesText = new StringBuilder();
                    for (XSLFShape shape : notes.getShapes()) {
                        if (shape instanceof XSLFTextShape textShape && isSpeakerNotes(textShape)) {
                            appendLine(notesText, textShape.getText());
                        }
                    }
                    if (!notesText.isEmpty()) {
                        sb.append("Notes: ").append(notesText);
                    }
                }
                sb.append('\n');
            }
            return sb.toString();
        } catch (NotOfficeXmlFileException | POIXMLException e) {
            throw new IOException("Failed to parse PPTX", e);
        }
    }

    /**
     * A notes sheet carries the speaker notes alongside slide-number, header, footer, date and
     * slide-thumbnail placeholders. Those hold chrome, not content — the page-number glyph, and the
     * notes master's prompt text ("Click to edit Master text styles") whenever a placeholder is
     * empty. Indexing them would stamp the same boilerplate onto every chunk of every deck, so only
     * the notes body (and any text box an author added themselves) is kept.
     */
    private boolean isSpeakerNotes(XSLFTextShape shape) {
        Placeholder type;
        try {
            type = shape.getTextType();
        } catch (IllegalArgumentException e) {
            return true; // not a placeholder at all — a text box the author dropped on the page
        }
        if (type == null) {
            return true;
        }
        return switch (type) {
            case SLIDE_NUMBER, DATETIME, HEADER, FOOTER, SLIDE_IMAGE, TITLE -> false;
            default -> true;
        };
    }

    /**
     * Flatten a workbook to text. Each row is prefixed with its sheet name so a chunk torn out of
     * the middle of a large sheet still carries the context of where it came from.
     */
    private String parseXlsx(InputStream content) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(content)) {
            DataFormatter formatter = new DataFormatter();
            StringBuilder sb = new StringBuilder();

            for (Sheet sheet : workbook) {
                sb.append("# Sheet: ").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    for (Cell cell : row) {
                        String value = formatter.formatCellValue(cell);
                        if (!value.isBlank()) {
                            if (!line.isEmpty()) {
                                line.append('\t');
                            }
                            line.append(value.strip());
                        }
                    }
                    appendLine(sb, line.toString());
                }
                sb.append('\n');
            }
            return sb.toString();
        } catch (NotOfficeXmlFileException | POIXMLException e) {
            throw new IOException("Failed to parse XLSX", e);
        }
    }

    /**
     * Extract readable text from HTML, dropping scripts/styles and keeping block boundaries as
     * newlines so the chunker splits on structure rather than mid-sentence.
     */
    private String parseHtml(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("script, style, noscript").remove();
        return blockAwareText(doc);
    }

    /** The readable part of a fetched web page. */
    public record WebPageContent(String title, String text) {}

    /**
     * Extract the readable body of a fetched web page.
     *
     * <p>Goes further than {@link #parseHtml}: a live page also carries site chrome \u2014 the nav bar,
     * the cookie banner, the footer links \u2014 which is identical on every page of the site and would
     * be indexed once per URL, drowning the actual content in boilerplate. So the chrome is dropped,
     * and when the page marks its content with {@code <main>} / {@code <article>} we index that
     * rather than the whole body.
     *
     * @param html    the fetched document
     * @param fallbackTitle used as the title when the page has no {@code <title>}
     */
    public WebPageContent parseWebPage(String html, String fallbackTitle) {
        Document doc = Jsoup.parse(html);
        doc.select("script, style, noscript, nav, header, footer, aside, form, iframe, svg, template")
                .remove();

        String title = doc.title();
        if (title == null || title.isBlank()) {
            title = fallbackTitle;
        }

        Element content = doc.selectFirst("main, article, [role=main]");
        Element root = content != null ? content : doc.body();
        if (root == null) {
            return new WebPageContent(title, "");
        }
        return new WebPageContent(title.strip(), blockAwareText(root));
    }

    /**
     * Flatten an element to text, turning block boundaries into newlines. Jsoup's own {@code text()}
     * collapses everything onto one line, which would glue the end of one paragraph to the start of
     * the next and give the chunker nothing to split on.
     */
    private String blockAwareText(Element root) {
        root.ownerDocument().outputSettings().prettyPrint(false);
        root.select("br, p, div, li, tr, section, article, h1, h2, h3, h4, h5, h6, blockquote")
                .append("\n");

        return root.wholeText()
                .replace('\u00a0', ' ')
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private void appendLine(StringBuilder sb, String text) {
        if (text != null && !text.isBlank()) {
            sb.append(text.strip()).append('\n');
        }
    }
}
