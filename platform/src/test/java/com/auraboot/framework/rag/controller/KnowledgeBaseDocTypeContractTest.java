package com.auraboot.framework.rag.controller;

import com.auraboot.framework.rag.service.DocumentParserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Supporting a document format means changing four things in lockstep, and dropping any one of them
 * fails silently in a different way:
 *
 * <ol>
 *   <li>the {@code chk_doc_type} CHECK constraint — miss it and the insert blows up at runtime;</li>
 *   <li>{@link DocumentParserService} — miss it and the document uploads, then fails to parse;</li>
 *   <li>{@link KnowledgeBaseController#resolveDocType} — miss it and the upload is rejected outright,
 *       no matter what the other three say;</li>
 *   <li>the accept list in the upload UI — miss it and the user can never pick the file.</li>
 * </ol>
 *
 * <p>This test pins 1-3 together. (4) is covered by the browser golden.
 */
@DisplayName("doc_type contract")
class KnowledgeBaseDocTypeContractTest {

    /** Extensions the upload UI offers — keep in step with the accept list in knowledge.$kbPid.tsx. */
    private static final List<String> ACCEPTED_EXTENSIONS =
            List.of("pdf", "docx", "pptx", "xlsx", "ppt", "xls", "md", "txt", "csv", "html", "png", "jpg", "jpeg", "gif", "webp");

    @Test
    @DisplayName("every extension the UI offers resolves to a type the parser can actually handle")
    void acceptedExtensionsResolveToParsableTypes() {
        for (String ext : ACCEPTED_EXTENSIONS) {
            String docType = KnowledgeBaseController.resolveDocType(ext);

            assertNotNull(docType, "." + ext + " is offered in the upload accept list but "
                    + "resolveDocType rejects it — the upload would fail before the row is created");
            assertTrue(DocumentParserService.SUPPORTED_DOC_TYPES.contains(docType),
                    "." + ext + " resolves to '" + docType + "', which the parser cannot handle");
        }
    }

    @Test
    @DisplayName("every type the parser supports is reachable from some upload extension")
    void everyParserTypeIsReachable() {
        for (String docType : DocumentParserService.SUPPORTED_DOC_TYPES) {
            boolean reachable = ACCEPTED_EXTENSIONS.stream()
                    .map(KnowledgeBaseController::resolveDocType)
                    .anyMatch(docType::equals);
            assertTrue(reachable, "the parser handles '" + docType + "' but no accepted extension "
                    + "maps to it — the format is dead code until resolveDocType learns about it");
        }
    }

    @Test
    @DisplayName("every supported type is allowed by the chk_doc_type CHECK constraint")
    void supportedTypesAreAllowedByTheCheckConstraint() throws IOException {
        String constraint = readChkDocTypeConstraint();

        for (String docType : DocumentParserService.SUPPORTED_DOC_TYPES) {
            assertTrue(constraint.contains("'" + docType + "'"),
                    "the parser handles '" + docType + "' but chk_doc_type does not allow it — "
                            + "the insert would fail with a CHECK violation. Constraint: " + constraint);
        }
    }

    @Test
    @DisplayName("legacy .ppt and .xls are accepted now that poi-scratchpad is a dependency")
    void legacyPptAndXlsAreAccepted() {
        assertEquals("ppt", KnowledgeBaseController.resolveDocType("ppt"));
        assertEquals("xls", KnowledgeBaseController.resolveDocType("xls"));
    }

    @Test
    @DisplayName(".doc is still refused — POI cannot create one, so the parser cannot be tested")
    void legacyDocIsStillRefused() {
        // POI reads .doc but cannot write one, so no fixture can be synthesised for it. Shipping an
        // untested binary parser is worse than a clear rejection at upload: the user would get a
        // document that ingests, reports completed, and contains whatever HWPF made of it.
        assertNull(KnowledgeBaseController.resolveDocType("doc"));
    }

    @Test
    @DisplayName("unknown extensions resolve to null so upload rejects them")
    void unknownExtensionsAreRejected() {
        assertNull(KnowledgeBaseController.resolveDocType("exe"));
        assertNull(KnowledgeBaseController.resolveDocType(null));
    }

    @Test
    @DisplayName("aliases and a leading dot resolve to the canonical type")
    void aliasesResolve() {
        assertEquals("md", KnowledgeBaseController.resolveDocType("markdown"));
        assertEquals("html", KnowledgeBaseController.resolveDocType("htm"));
        assertEquals("pdf", KnowledgeBaseController.resolveDocType(".PDF"));
    }

    /**
     * Read the constraint out of db/snapshots/schema-current.sql — the Flyway-generated snapshot
     * that golden-stack and fresh-stack apply directly, so it is the copy that actually decides
     * whether an insert succeeds on a fresh environment.
     */
    private String readChkDocTypeConstraint() throws IOException {
        Path schema = Path.of("src/main/resources/db/snapshots/schema-current.sql");
        assertTrue(Files.exists(schema), "snapshot not found at " + schema.toAbsolutePath());

        String sql = Files.readString(schema, StandardCharsets.UTF_8);
        // pg_dump renders the CHECK on one line, normalising `IN (...)` to `= ANY (ARRAY[...])`:
        //   CONSTRAINT chk_doc_type CHECK (((doc_type)::text = ANY ((ARRAY['pdf'::character varying, ...])::text[])))
        // Capture the whole CHECK body — callers only test .contains("'<type>'").
        Matcher m = Pattern.compile("CONSTRAINT chk_doc_type CHECK \\((.*)\\)").matcher(sql);
        assertTrue(m.find(), "chk_doc_type constraint not found in snapshot");
        return m.group(1);
    }
}
