package com.auraboot.framework.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException;
import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.hslf.usermodel.HSLFNotes;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Extracts plain text from knowledge-base source documents.
 *
 * <p>Parsers read from an {@link InputStream}, never from a filesystem path: the file may live in
 * MinIO / S3 / OSS (see {@code aura.storage.type}), where the {@code local_path} recorded on the
 * file entity is a remote object key, not something {@code FileInputStream} can open. Callers
 * obtain the stream from {@code StorageProvider.download(key)}.
 *
 * <p>Legacy binary {@code .ppt} and {@code .xls} are supported (poi-scratchpad). {@code .doc} is
 * not: POI can read one but cannot create one, so there is no way to build a fixture for it, and an
 * untested binary parser is worse than a clear rejection at upload. Hand us a real .doc and it is a
 * few minutes' work.
 */
@Slf4j
@Service
public class DocumentParserService {

    /**
     * Doc types a knowledge base accepts. Must stay in sync with the {@code chk_doc_type} CHECK
     * constraint and with {@code KnowledgeBaseController.resolveDocType}.
     *
     * <p>{@code image} is in the set but is not handled here: an image carries no text to extract,
     * it is described by {@code KbImageUnderstandingService} and the description is what gets
     * indexed. {@link #parse} says so explicitly rather than letting it fall through.
     */
    public static final Set<String> SUPPORTED_DOC_TYPES =
            Set.of("pdf", "docx", "pptx", "xlsx", "ppt", "xls", "md", "txt", "csv", "html", "image");

    /** A picture found inside a document — typically the chart the slide is actually about. */
    public record EmbeddedImage(String mediaType, byte[] data, String location) {}

    /** What a vision model will accept. A deck can also embed EMF/WMF/TIFF; those are skipped. */
    private static final Set<String> VISION_MEDIA_TYPES =
            Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    /**
     * Below this, on either side, a picture is chrome: a logo, an icon, a bullet glyph, the divider
     * on the master slide. Describing those would spend a vision call each and then bury the deck's
     * actual content under thirty descriptions of the company logo.
     */
    private static final int MIN_IMAGE_DIMENSION_PX = 200;

    /** A hard ceiling on vision calls per document — each one is a paid, multi-second round trip. */
    private static final int MAX_EMBEDDED_IMAGES = 20;

    /** Pages of a scan we will rasterise and read. Beyond this a document is a book, not a file. */
    private static final int MAX_RENDERED_PDF_PAGES = 10;

    /**
     * Pull the pictures out of a document, so a caller with a vision model can read them.
     *
     * <p>This is what makes "upload the quarterly deck" work as a user means it. A slide's whole
     * point is often a chart, and a chart is a picture — extracting only the text frames returns the
     * title and the footer and silently drops the thing the slide was made to show.
     *
     * <p>Understanding is deliberately not done here: this class stays a pure parser with no LLM
     * dependency, so it can be unit-tested against real containers without a provider. The caller
     * ({@code DocumentProcessingService}) owns the vision model and decides what to do with these.
     *
     * @return the images worth looking at, in document order; empty for formats with no pictures
     */
    public List<EmbeddedImage> extractEmbeddedImages(InputStream content, String docType)
            throws IOException {
        return switch (docType.toLowerCase()) {
            case "pptx" -> pptxImages(content);
            case "docx" -> docxImages(content);
            case "pdf" -> pdfImages(content);
            default -> List.of();
        };
    }

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
            case "ppt" -> parsePpt(content);
            case "xls" -> parseXls(content);
            case "md", "txt", "csv" -> new String(content.readAllBytes(), StandardCharsets.UTF_8);
            case "html" -> parseHtml(new String(content.readAllBytes(), StandardCharsets.UTF_8));
            case "image" -> throw new IllegalArgumentException(
                    "images are not parsed as text — they are described by KbImageUnderstandingService; "
                            + "DocumentProcessingService routes them there before reaching this parser");
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
            return parseWorkbook(workbook);
        } catch (NotOfficeXmlFileException | POIXMLException e) {
            throw new IOException("Failed to parse XLSX", e);
        }
    }

    /** The Excel 97 binary format. Same shape once POI has it open — Sheet/Row/Cell are shared. */
    private String parseXls(InputStream content) throws IOException {
        try (HSSFWorkbook workbook = new HSSFWorkbook(content)) {
            return parseWorkbook(workbook);
        }
    }

    /**
     * Flatten a workbook to text. Each sheet's rows are prefixed with the sheet name so a chunk torn
     * out of the middle of a large sheet still carries the context of where it came from.
     */
    private String parseWorkbook(Workbook workbook) {
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
    }

    /**
     * The PowerPoint 97 binary format. Same idea as the OOXML path — slide text plus speaker notes —
     * but a different object model: HSLF, not XSLF.
     */
    private String parsePpt(InputStream content) throws IOException {
        try (HSLFSlideShow ppt = new HSLFSlideShow(content)) {
            StringBuilder sb = new StringBuilder();
            int slideNo = 0;
            for (HSLFSlide slide : ppt.getSlides()) {
                slideNo++;
                sb.append("# Slide ").append(slideNo).append('\n');

                for (HSLFShape shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape textShape) {
                        appendLine(sb, textShape.getText());
                    }
                }

                HSLFNotes notes = slide.getNotes();
                if (notes != null) {
                    StringBuilder notesText = new StringBuilder();
                    for (HSLFShape shape : notes.getShapes()) {
                        if (shape instanceof HSLFTextShape textShape) {
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

    // =========================================================================
    // Embedded pictures
    // =========================================================================

    private List<EmbeddedImage> pptxImages(InputStream content) throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow(content)) {
            List<EmbeddedImage> images = new ArrayList<>();
            int slideNo = 0;
            for (XSLFSlide slide : ppt.getSlides()) {
                slideNo++;
                for (XSLFShape shape : slide.getShapes()) {
                    if (!(shape instanceof XSLFPictureShape picture)) {
                        continue;
                    }
                    XSLFPictureData data = picture.getPictureData();
                    addIfWorthReading(images, data.getContentType(), data.getData(),
                            "Slide " + slideNo);
                    if (images.size() >= MAX_EMBEDDED_IMAGES) {
                        log.info("Deck has more than {} pictures — reading the first {}",
                                MAX_EMBEDDED_IMAGES, MAX_EMBEDDED_IMAGES);
                        return images;
                    }
                }
            }
            return images;
        } catch (NotOfficeXmlFileException | POIXMLException e) {
            throw new IOException("Failed to read pictures from PPTX", e);
        }
    }

    private List<EmbeddedImage> docxImages(InputStream content) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(content)) {
            List<EmbeddedImage> images = new ArrayList<>();
            int index = 0;
            for (XWPFPictureData data : doc.getAllPictures()) {
                index++;
                // A Word document has no slide numbers to anchor to; the ordinal is what we have.
                addIfWorthReading(images, mediaTypeOf(data.getPictureTypeEnum()), data.getData(),
                        "Image " + index);
                if (images.size() >= MAX_EMBEDDED_IMAGES) {
                    return images;
                }
            }
            return images;
        } catch (NotOfficeXmlFileException | POIXMLException e) {
            throw new IOException("Failed to read pictures from DOCX", e);
        }
    }

    private List<EmbeddedImage> pdfImages(InputStream content) throws IOException {
        try (PDDocument doc = PDDocument.load(content)) {
            List<EmbeddedImage> images = collectPdfXObjectImages(doc);
            if (!images.isEmpty()) {
                return images;
            }

            // Nothing is embedded as an XObject. If the document has no text layer either, it is a
            // scan: the page *is* the content — drawn as inline image data or vector strokes that no
            // extraction reaches. Render the pages and let the vision model read them, which is the
            // only thing that turns a photographed invoice into something searchable.
            //
            // Rendering is expensive, so it happens only here, when there is provably nothing else.
            if (!new PDFTextStripper().getText(doc).isBlank()) {
                return images;  // an ordinary text PDF that simply has no pictures
            }
            return renderPdfPages(doc);
        }
    }

    /** Rasterise the pages of a scan so a vision model can read them. */
    private List<EmbeddedImage> renderPdfPages(PDDocument doc) throws IOException {
        PDFRenderer renderer = new PDFRenderer(doc);
        List<EmbeddedImage> pages = new ArrayList<>();

        int limit = Math.min(doc.getNumberOfPages(), MAX_RENDERED_PDF_PAGES);
        for (int i = 0; i < limit; i++) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            // 150 DPI: enough for a model to read body text, without producing megabytes per page.
            if (!ImageIO.write(renderer.renderImageWithDPI(i, 150), "png", png)) {
                continue;
            }
            pages.add(new EmbeddedImage("image/png", png.toByteArray(), "Page " + (i + 1)));
        }
        if (doc.getNumberOfPages() > limit) {
            log.info("Scanned PDF has {} pages — reading the first {}",
                    doc.getNumberOfPages(), limit);
        }
        return pages;
    }

    private List<EmbeddedImage> collectPdfXObjectImages(PDDocument doc) throws IOException {
        List<EmbeddedImage> images = new ArrayList<>();
        int pageNo = 0;
        for (PDPage page : doc.getPages()) {
            pageNo++;
            PDResources resources = page.getResources();
            if (resources == null) {
                continue;
            }
            for (COSName name : resources.getXObjectNames()) {
                PDXObject xObject;
                try {
                    xObject = resources.getXObject(name);
                } catch (IOException e) {
                    log.debug("Skipping unreadable XObject on page {}: {}", pageNo, e.getMessage());
                    continue;
                }
                if (!(xObject instanceof PDImageXObject image)) {
                    continue;
                }
                // A PDF image is a raw raster in the file's own colour space, not a PNG — it has
                // to be re-encoded before a vision API will take it.
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                if (!ImageIO.write(image.getImage(), "png", png)) {
                    continue;
                }
                addIfWorthReading(images, "image/png", png.toByteArray(), "Page " + pageNo);
                if (images.size() >= MAX_EMBEDDED_IMAGES) {
                    return images;
                }
            }
        }
        return images;
    }

    /** Keep a picture only if a vision model can read it and it is big enough to be content. */
    private void addIfWorthReading(List<EmbeddedImage> images, String mediaType, byte[] data,
                                     String location) {
        if (mediaType == null || !VISION_MEDIA_TYPES.contains(mediaType.toLowerCase(Locale.ROOT))) {
            return;
        }
        if (data == null || data.length == 0) {
            return;
        }
        int[] size = readImageSize(data);
        if (size == null || size[0] < MIN_IMAGE_DIMENSION_PX || size[1] < MIN_IMAGE_DIMENSION_PX) {
            return;
        }
        images.add(new EmbeddedImage(mediaType.toLowerCase(Locale.ROOT), data, location));
    }

    /**
     * Width and height from the image header alone. Decoding the whole raster just to learn that a
     * 32×32 icon is an icon would be the expensive way to throw it away.
     */
    private int[] readImageSize(byte[] data) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * POI's {@code Document.PICTURE_TYPE_*} ints look like constants but carry no ConstantValue, so
     * they cannot be switch labels. The enum is what they are anyway.
     */
    private String mediaTypeOf(PictureType type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case PNG -> "image/png";
            case JPEG -> "image/jpeg";
            case GIF -> "image/gif";
            default -> "";  // EMF / WMF / PICT / DIB / TIFF — no vision API takes these
        };
    }

    private void appendLine(StringBuilder sb, String text) {
        if (text != null && !text.isBlank()) {
            sb.append(text.strip()).append('\n');
        }
    }
}
