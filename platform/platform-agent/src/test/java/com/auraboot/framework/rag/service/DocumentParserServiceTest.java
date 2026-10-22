package com.auraboot.framework.rag.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.util.Units;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixtures are built in-memory with the same libraries that read them back, so every positive test
 * asserts on text that provably came out of a real OOXML/PDF container — not out of a file name.
 */
@DisplayName("DocumentParserService")
class DocumentParserServiceTest {

    private final DocumentParserService service = new DocumentParserService();

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    // -------------------------------------------------------------------------
    // Plain text formats
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("parse reads markdown verbatim")
    void parseMarkdown() throws IOException {
        String text = service.parse(stream("# Title\nbody"), "md");
        assertTrue(text.contains("# Title"));
        assertTrue(text.contains("body"));
    }

    @Test
    @DisplayName("parse reads txt verbatim")
    void parseTxt() throws IOException {
        assertEquals("plain text content", service.parse(stream("plain text content"), "txt"));
    }

    @Test
    @DisplayName("parse reads csv verbatim")
    void parseCsv() throws IOException {
        assertTrue(service.parse(stream("a,b\n1,2"), "csv").contains("a,b"));
    }

    @Test
    @DisplayName("parse handles uppercase docType")
    void parseUppercaseType() throws IOException {
        assertEquals("x", service.parse(stream("x"), "TXT"));
    }

    @Test
    @DisplayName("parse rejects unsupported docType with IllegalArgumentException")
    void parseUnsupported() {
        assertThrows(IllegalArgumentException.class, () -> service.parse(stream("x"), "bin"));
    }

    // -------------------------------------------------------------------------
    // HTML (Jsoup)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("HTML")
    class Html {

        @Test
        @DisplayName("extracts text, decodes entities, drops tags")
        void parseHtml() throws IOException {
            String html = "<html><body><p>Hello&nbsp;<b>World</b> &amp; &lt;there&gt;</p></body></html>";
            String text = service.parse(stream(html), "html");

            assertTrue(text.contains("Hello"));
            assertTrue(text.contains("World"));
            assertTrue(text.contains("&"));
            assertTrue(text.contains("<there>"));
            assertFalse(text.contains("<p>"));
            assertFalse(text.contains("&nbsp;"));
            assertFalse(text.contains("\u00a0"), "non-breaking space should be normalised");
        }

        @Test
        @DisplayName("drops script and style content that would otherwise be indexed as prose")
        void dropsScriptAndStyle() throws IOException {
            String html = "<html><head><style>.a{color:red}</style></head>"
                    + "<body><script>var secret = 'tracking pixel';</script>"
                    + "<p>Real content</p></body></html>";
            String text = service.parse(stream(html), "html");

            assertTrue(text.contains("Real content"));
            assertFalse(text.contains("tracking pixel"), "script body must not reach the index");
            assertFalse(text.contains("color:red"), "style body must not reach the index");
        }

        @Test
        @DisplayName("web page: drops site chrome and prefers the marked-up main content")
        void parseWebPageDropsChrome() {
            String html = """
                    <html><head><title>Refund policy — Acme</title></head>
                    <body>
                      <nav><a href="/">Home</a><a href="/pricing">Pricing</a></nav>
                      <header>Acme Corp</header>
                      <aside>Subscribe to our newsletter</aside>
                      <main><p>Refunds are issued within 30 days of purchase.</p></main>
                      <footer>Copyright 2026 Acme. All rights reserved.</footer>
                    </body></html>
                    """;

            var page = service.parseWebPage(html, "http://acme.test/refunds");

            assertEquals("Refund policy — Acme", page.title());
            assertTrue(page.text().contains("Refunds are issued within 30 days"),
                    "the actual content went missing: " + page.text());
            // Chrome is identical on every page of a site. Indexing it once per URL would bury the
            // content under navigation links and copyright notices.
            assertFalse(page.text().contains("Pricing"), "nav leaked in: " + page.text());
            assertFalse(page.text().contains("newsletter"), "aside leaked in: " + page.text());
            assertFalse(page.text().contains("All rights reserved"), "footer leaked in: " + page.text());
        }

        @Test
        @DisplayName("web page: falls back to the body when the page marks no main content")
        void parseWebPageFallsBackToBody() {
            String html = "<html><head><title>Notes</title></head>"
                    + "<body><div><p>Server maintenance is scheduled for Sunday.</p></div></body></html>";

            var page = service.parseWebPage(html, "http://acme.test/notes");

            assertEquals("Notes", page.title());
            assertTrue(page.text().contains("Server maintenance is scheduled for Sunday"), page.text());
        }

        @Test
        @DisplayName("web page: falls back to the URL when the page has no title")
        void parseWebPageFallsBackToUrlAsTitle() {
            var page = service.parseWebPage("<html><body><p>Body only</p></body></html>",
                    "http://acme.test/untitled");

            assertEquals("http://acme.test/untitled", page.title());
            assertTrue(page.text().contains("Body only"));
        }

        @Test
        @DisplayName("keeps block boundaries as newlines so chunks do not run sentences together")
        void keepsBlockBoundaries() throws IOException {
            String html = "<body><h1>Title</h1><p>First para</p><p>Second para</p>"
                    + "<ul><li>Item one</li><li>Item two</li></ul></body>";
            String text = service.parse(stream(html), "html");

            assertFalse(text.contains("First paraSecond para"), "paragraphs must not be glued: " + text);
            assertTrue(text.contains("Title\n"), "block elements should be newline-separated: " + text);
            assertTrue(text.contains("Item one"));
            assertTrue(text.contains("Item two"));
        }
    }

    // -------------------------------------------------------------------------
    // PDF
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("PDF")
    class Pdf {

        @Test
        @DisplayName("extracts the text drawn on the page")
        void parsePdfContent() throws IOException {
            byte[] pdf = pdfWith("Quarterly revenue grew 12 percent");

            String text = service.parse(new ByteArrayInputStream(pdf), "pdf");

            assertTrue(text.contains("Quarterly revenue grew 12 percent"),
                    "expected the real page text, got: " + text);
        }

        @Test
        @DisplayName("throws IOException for non-PDF input")
        void parsePdfBadFile() {
            assertThrows(IOException.class, () -> service.parse(stream("not a pdf"), "pdf"));
        }

        private byte[] pdfWith(String body) throws IOException {
            try (PDDocument doc = new PDDocument()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA, 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(body);
                    cs.endText();
                }
                doc.save(out);
                return out.toByteArray();
            }
        }
    }

    // -------------------------------------------------------------------------
    // DOCX
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("DOCX")
    class Docx {

        @Test
        @DisplayName("extracts paragraphs and table cells")
        void parseDocxContent() throws IOException {
            byte[] docx;
            try (XWPFDocument doc = new XWPFDocument()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                doc.createParagraph().createRun().setText("Refund policy overview");
                var table = doc.createTable(1, 2);
                table.getRow(0).getCell(0).setText("Window");
                table.getRow(0).getCell(1).setText("30 days");
                doc.write(out);
                docx = out.toByteArray();
            }

            String text = service.parse(new ByteArrayInputStream(docx), "docx");

            assertTrue(text.contains("Refund policy overview"), "missing paragraph text: " + text);
            assertTrue(text.contains("Window"), "missing table cell: " + text);
            assertTrue(text.contains("30 days"), "missing table cell: " + text);
        }

        @Test
        @DisplayName("throws IOException for non-DOCX input")
        void parseDocxBadFile() {
            assertThrows(IOException.class, () -> service.parse(stream("not a docx"), "docx"));
        }
    }

    // -------------------------------------------------------------------------
    // PPTX — the format this milestone exists for
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("PPTX")
    class Pptx {

        @Test
        @DisplayName("extracts slide body text and speaker notes, slide by slide")
        void parsePptxContent() throws IOException {
            byte[] pptx;
            try (XMLSlideShow ppt = new XMLSlideShow()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                XSLFSlide slide1 = ppt.createSlide();
                XSLFTextBox box1 = slide1.createTextBox();
                box1.setText("Q3 East China revenue fell 12 percent");

                XSLFSlide slide2 = ppt.createSlide();
                XSLFTextBox box2 = slide2.createTextBox();
                box2.setText("Mitigation plan");
                setSpeakerNotes(ppt, slide2,
                        "Channel conflict with the Hangzhou distributor is the root cause");

                ppt.write(out);
                pptx = out.toByteArray();
            }

            String text = service.parse(new ByteArrayInputStream(pptx), "pptx");

            // The point of the format: the words on the slide, not the file name.
            assertTrue(text.contains("Q3 East China revenue fell 12 percent"),
                    "missing slide 1 body text: " + text);
            assertTrue(text.contains("Mitigation plan"), "missing slide 2 body text: " + text);
            assertTrue(text.contains("Channel conflict with the Hangzhou distributor"),
                    "speaker notes must be indexed — they carry what the slide only gestures at: " + text);
            assertTrue(text.contains("# Slide 1") && text.contains("# Slide 2"),
                    "slides should stay separable for chunking: " + text);
        }

        @Test
        @DisplayName("does not index the notes-sheet chrome (page number, master prompt text)")
        void skipsNotesChrome() throws IOException {
            byte[] pptx;
            try (XMLSlideShow ppt = new XMLSlideShow()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                XSLFSlide slide = ppt.createSlide();
                slide.createTextBox().setText("Revenue by region");
                setSpeakerNotes(ppt, slide, "Numbers are provisional until the audit closes");
                ppt.write(out);
                pptx = out.toByteArray();
            }

            String text = service.parse(new ByteArrayInputStream(pptx), "pptx");

            assertTrue(text.contains("Numbers are provisional"), "real notes went missing: " + text);
            // A notes sheet also carries slide-number / header / footer / date placeholders. Their
            // text is boilerplate, and indexing it stamps the same noise onto every deck.
            assertFalse(text.contains("Click to edit Master text styles"),
                    "notes master prompt text leaked into the index: " + text);
            assertFalse(text.contains("Second level"),
                    "notes master prompt text leaked into the index: " + text);
            assertFalse(text.contains("‹#›"),
                    "the slide-number placeholder leaked into the index: " + text);
        }

        /** Put the text where PowerPoint puts speaker notes: the notes sheet's BODY placeholder. */
        private void setSpeakerNotes(XMLSlideShow ppt, XSLFSlide slide, String notes) {
            XSLFNotes notesSheet = ppt.getNotesSlide(slide);
            for (XSLFShape shape : notesSheet.getShapes()) {
                if (shape instanceof XSLFTextShape textShape
                        && textShape.getTextType() == Placeholder.BODY) {
                    textShape.setText(notes);
                    return;
                }
            }
            throw new IllegalStateException("notes sheet has no BODY placeholder");
        }

        @Test
        @DisplayName("throws IOException for non-PPTX input (incl. legacy binary .ppt)")
        void parsePptxBadFile() {
            assertThrows(IOException.class, () -> service.parse(stream("not a pptx"), "pptx"));
        }
    }

    // -------------------------------------------------------------------------
    // XLSX
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("XLSX")
    class Xlsx {

        @Test
        @DisplayName("flattens sheets to rows, keeping the header and sheet name as context")
        void parseXlsxContent() throws IOException {
            byte[] xlsx;
            try (XSSFWorkbook wb = new XSSFWorkbook()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                var sheet = wb.createSheet("SLA");
                var header = sheet.createRow(0);
                header.createCell(0).setCellValue("Tier");
                header.createCell(1).setCellValue("Response time");
                var row = sheet.createRow(1);
                row.createCell(0).setCellValue("Enterprise");
                row.createCell(1).setCellValue("4 hours");
                wb.write(out);
                xlsx = out.toByteArray();
            }

            String text = service.parse(new ByteArrayInputStream(xlsx), "xlsx");

            assertTrue(text.contains("# Sheet: SLA"), "sheet name gives the chunk its context: " + text);
            assertTrue(text.contains("Tier"), "missing header: " + text);
            assertTrue(text.contains("Response time"), "missing header: " + text);
            assertTrue(text.contains("Enterprise"), "missing data cell: " + text);
            assertTrue(text.contains("4 hours"), "missing data cell: " + text);
        }

        @Test
        @DisplayName("renders numeric cells as displayed, not as raw doubles")
        void formatsCellValues() throws IOException {
            byte[] xlsx;
            try (XSSFWorkbook wb = new XSSFWorkbook()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                wb.createSheet("Prices").createRow(0).createCell(0).setCellValue(1500);
                wb.write(out);
                xlsx = out.toByteArray();
            }

            String text = service.parse(new ByteArrayInputStream(xlsx), "xlsx");

            assertTrue(text.contains("1500"), "expected 1500, got: " + text);
            assertFalse(text.contains("1500.0"), "raw double leaked into the index: " + text);
        }

        @Test
        @DisplayName("throws IOException for non-XLSX input (incl. legacy binary .xls)")
        void parseXlsxBadFile() {
            assertThrows(IOException.class, () -> service.parse(stream("not a xlsx"), "xlsx"));
        }
    }

    // -------------------------------------------------------------------------
    // Pictures inside documents — the chart the slide is actually about
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("embedded images")
    class EmbeddedImages {

        /** A PNG of the given size. 400x300 stands in for a chart; 64x64 for a logo. */
        private byte[] png(int width, int height) throws IOException {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(Color.BLUE);
            g.fillRect(10, 10, Math.max(1, width / 3), Math.max(1, height / 2));
            g.dispose();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        }

        @Test
        @DisplayName("PPTX: the chart on the slide is extracted, the logo on it is not")
        void pptxPicturesAreExtractedAndFiltered() throws IOException {
            byte[] pptx;
            try (XMLSlideShow ppt = new XMLSlideShow()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();

                XSLFSlide slide = ppt.createSlide();
                slide.createTextBox().setText("Q3 regional performance");

                // The chart the slide exists for.
                XSLFPictureData chart = ppt.addPicture(png(400, 300), PictureData.PictureType.PNG);
                slide.createPicture(chart).setAnchor(new Rectangle2D.Double(50, 50, 400, 300));

                // The company logo, in the corner of every slide ever made.
                XSLFPictureData logo = ppt.addPicture(png(64, 64), PictureData.PictureType.PNG);
                slide.createPicture(logo).setAnchor(new Rectangle2D.Double(600, 20, 64, 64));

                ppt.write(out);
                pptx = out.toByteArray();
            }

            var images = service.extractEmbeddedImages(new ByteArrayInputStream(pptx), "pptx");

            // Two pictures went in. Only the one that could be content comes out — describing the
            // logo would cost a vision call per slide and bury the deck under its own letterhead.
            assertEquals(1, images.size(),
                    "expected only the chart, got: " + images.stream().map(i -> i.location()).toList());
            assertEquals("image/png", images.get(0).mediaType());
            assertEquals("Slide 1", images.get(0).location(),
                    "the location is what tells the reader which slide the chart was on");
            assertTrue(images.get(0).data().length > 0);
        }

        @Test
        @DisplayName("PPTX: slide numbers are carried through, so a description can be placed")
        void pptxLocationsTrackSlides() throws IOException {
            byte[] pptx;
            try (XMLSlideShow ppt = new XMLSlideShow()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                for (int i = 0; i < 3; i++) {
                    XSLFSlide slide = ppt.createSlide();
                    XSLFPictureData pic = ppt.addPicture(png(400, 300), PictureData.PictureType.PNG);
                    slide.createPicture(pic).setAnchor(new Rectangle2D.Double(50, 50, 400, 300));
                }
                ppt.write(out);
                pptx = out.toByteArray();
            }

            var images = service.extractEmbeddedImages(new ByteArrayInputStream(pptx), "pptx");

            assertEquals(3, images.size());
            assertEquals(List.of("Slide 1", "Slide 2", "Slide 3"),
                    images.stream().map(i -> i.location()).toList());
        }

        @Test
        @DisplayName("DOCX: pictures are extracted, small ones filtered")
        void docxPicturesAreExtracted() throws Exception {
            byte[] docx;
            try (XWPFDocument doc = new XWPFDocument()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                var run = doc.createParagraph().createRun();
                run.addPicture(new ByteArrayInputStream(png(400, 300)),
                        org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG, "chart.png",
                        Units.toEMU(400), Units.toEMU(300));
                run.addPicture(new ByteArrayInputStream(png(48, 48)),
                        org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG, "icon.png",
                        Units.toEMU(48), Units.toEMU(48));
                doc.write(out);
                docx = out.toByteArray();
            }

            var images = service.extractEmbeddedImages(new ByteArrayInputStream(docx), "docx");

            assertEquals(1, images.size(), "the 48x48 icon should have been filtered out");
            assertEquals("image/png", images.get(0).mediaType());
        }

        @Test
        @DisplayName("formats with no pictures yield none, rather than failing")
        void formatsWithoutPicturesYieldNothing() throws IOException {
            assertTrue(service.extractEmbeddedImages(stream("plain text"), "txt").isEmpty());
            assertTrue(service.extractEmbeddedImages(stream("a,b"), "csv").isEmpty());
            assertTrue(service.extractEmbeddedImages(stream("<p>hi</p>"), "html").isEmpty());
        }
    }

    // -------------------------------------------------------------------------
    // Legacy binary formats — the files people actually still have
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("legacy Office")
    class Legacy {

        @Test
        @DisplayName("PPT (PowerPoint 97): slide text and speaker notes come out")
        void parsePptContent() throws IOException {
            byte[] ppt;
            try (HSLFSlideShow show = new HSLFSlideShow()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                HSLFSlide slide = show.createSlide();
                HSLFTextBox box = slide.createTextBox();
                box.setText("Legacy deck: Q3 East China fell 12 percent");
                show.write(out);
                ppt = out.toByteArray();
            }

            String text = service.parse(new ByteArrayInputStream(ppt), "ppt");

            assertTrue(text.contains("Legacy deck: Q3 East China fell 12 percent"),
                    "the slide text did not come out of the .ppt: " + text);
            assertTrue(text.contains("# Slide 1"), "slides should stay separable: " + text);
        }

        @Test
        @DisplayName("XLS (Excel 97): sheets flatten the same way xlsx does")
        void parseXlsContent() throws IOException {
            byte[] xls;
            try (HSSFWorkbook wb = new HSSFWorkbook()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                var sheet = wb.createSheet("SLA");
                var header = sheet.createRow(0);
                header.createCell(0).setCellValue("Tier");
                header.createCell(1).setCellValue("Response time");
                var row = sheet.createRow(1);
                row.createCell(0).setCellValue("Enterprise");
                row.createCell(1).setCellValue("4 hours");
                wb.write(out);
                xls = out.toByteArray();
            }

            String text = service.parse(new ByteArrayInputStream(xls), "xls");

            assertTrue(text.contains("# Sheet: SLA"), "sheet name gives the chunk context: " + text);
            assertTrue(text.contains("Enterprise"), "missing data cell: " + text);
            assertTrue(text.contains("4 hours"), "missing data cell: " + text);
        }

        @Test
        @DisplayName("XLS numbers render as displayed, not as raw doubles")
        void xlsFormatsNumbers() throws IOException {
            byte[] xls;
            try (HSSFWorkbook wb = new HSSFWorkbook()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                wb.createSheet("Prices").createRow(0).createCell(0).setCellValue(1500);
                wb.write(out);
                xls = out.toByteArray();
            }

            String text = service.parse(new ByteArrayInputStream(xls), "xls");

            assertTrue(text.contains("1500"), text);
            assertFalse(text.contains("1500.0"), "raw double leaked into the index: " + text);
        }

        @Test
        @DisplayName("a .pptx handed in as .ppt is refused rather than silently mangled")
        void wrongContainerForPpt() {
            assertThrows(IOException.class, () -> service.parse(stream("not a ppt"), "ppt"));
        }
    }

    // -------------------------------------------------------------------------
    // Scanned PDFs — a page that is a picture of text
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("scanned PDF")
    class ScannedPdf {

        /** A page of text, as an image — what a scanner produces. */
        private BufferedImage scanOfAPage() {
            BufferedImage img = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 800, 400);
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.PLAIN, 28));
            g.drawString("INVOICE 2026-0042", 60, 80);
            g.drawString("Total due: 8750 CNY", 60, 140);
            g.dispose();
            return img;
        }

        @Test
        @DisplayName("a scan embedded as an image is extracted for the vision model")
        void scanAsXObjectIsExtracted() throws IOException {
            byte[] pdf;
            try (PDDocument doc = new PDDocument()) {
                PDPage page = new PDPage();
                doc.addPage(page);
                PDImageXObject img = LosslessFactory.createFromImage(doc, scanOfAPage());
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(img, 20, 300, 560, 280);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                doc.save(out);
                pdf = out.toByteArray();
            }

            // No text layer at all — this is the defining property of a scan.
            assertTrue(service.parse(new ByteArrayInputStream(pdf), "pdf").isBlank(),
                    "a scan has no text layer; if this has one the fixture is wrong");

            var images = service.extractEmbeddedImages(new ByteArrayInputStream(pdf), "pdf");

            assertEquals(1, images.size(), "the scanned page was not extracted");
            assertEquals("Page 1", images.get(0).location());
            assertEquals("image/png", images.get(0).mediaType());
        }

        @Test
        @DisplayName("a scan drawn as vectors, with nothing to extract, is rendered instead")
        void scanWithNoExtractableImageIsRendered() throws IOException {
            // No text, no image XObject — the page is strokes. Nothing to pull out; the only way to
            // read it is to rasterise the page and look at it.
            byte[] pdf;
            try (PDDocument doc = new PDDocument()) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.setStrokingColor(Color.BLACK);
                    for (int i = 0; i < 12; i++) {
                        cs.moveTo(60, 700 - i * 20);
                        cs.lineTo(500, 700 - i * 20);
                    }
                    cs.stroke();
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                doc.save(out);
                pdf = out.toByteArray();
            }

            assertTrue(service.parse(new ByteArrayInputStream(pdf), "pdf").isBlank());

            var images = service.extractEmbeddedImages(new ByteArrayInputStream(pdf), "pdf");

            assertEquals(1, images.size(),
                    "a page with nothing extractable must be rendered, or the document ingests as empty");
            assertEquals("Page 1", images.get(0).location());
            assertTrue(images.get(0).data().length > 1000, "the rendered page looks empty");
        }

        @Test
        @DisplayName("an ordinary text PDF is not rendered — that would be paying to read what we can already read")
        void textPdfIsNotRendered() throws IOException {
            byte[] pdf;
            try (PDDocument doc = new PDDocument()) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA, 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("This page has a perfectly good text layer");
                    cs.endText();
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                doc.save(out);
                pdf = out.toByteArray();
            }

            var images = service.extractEmbeddedImages(new ByteArrayInputStream(pdf), "pdf");

            assertTrue(images.isEmpty(),
                    "a text PDF with no pictures must not be rasterised — that is a vision call per page, "
                            + "paid, to read text we already have");
        }
    }
}
