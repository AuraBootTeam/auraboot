package com.auraboot.framework.rag.service;

import com.auraboot.framework.common.util.PinnedHttpRequests;
import com.auraboot.framework.common.util.SsrfValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Ingest a single web page into a knowledge base by URL (G2-3).
 *
 * <p><b>This fetches a URL chosen by a tenant user, so it is an SSRF sink.</b> Without validation,
 * anyone holding {@code AI_KNOWLEDGE_MANAGE} could make the backend fetch cloud metadata
 * ({@code http://169.254.169.254/latest/meta-data/}), an internal admin port, or {@code localhost},
 * and then read the response back out of the knowledge base. Every request therefore goes through
 * {@link SsrfValidator} (which rejects private/loopback/link-local addresses, non-HTTP schemes and
 * management ports) and is sent with the validated IP pinned via {@link PinnedHttpRequests}, so a
 * DNS answer cannot flip to an internal address between the check and the connect.
 *
 * <p>Scope: <b>one URL, fetched now</b>. Crawling a whole site is the crawler plugin's job — it has
 * the queueing, politeness and scheduling this path deliberately does not.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbUrlIngestService {

    /** Anything larger is not a document someone meant to put in a knowledge base. */
    static final int MAX_CONTENT_BYTES = 5 * 1024 * 1024;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);
    private static final String USER_AGENT = "AuraBot-KnowledgeIngest/1.0";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER) // a redirect is a second, unvalidated target
            .build();

    private final DocumentParserService parserService;
    private final KbTextIngestService textIngestService;

    /**
     * Fetch a URL and ingest its readable content.
     *
     * @return the pid of the created knowledge-base document
     * @throws IllegalArgumentException if the URL is not safe to fetch, the host does not resolve,
     *                                  the response is not a fetchable HTML page, or it yields no text
     * @throws IOException              if the fetch itself fails
     */
    public String ingestUrl(long tenantId, String kbPid, String url) throws IOException {
        // Throws for private/loopback/link-local targets, non-HTTP schemes and blocked ports.
        SsrfValidator.ValidatedTarget target = SsrfValidator.validate(url);
        if (target == null) {
            // DNS did not resolve. Not an SSRF concern, but there is nothing to fetch either.
            throw new IllegalArgumentException("Host could not be resolved: " + url);
        }

        HttpRequest request = PinnedHttpRequests.newPinnedRequestBuilder(target)
                .timeout(READ_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }

        if (response.statusCode() / 100 != 2) {
            // Redirects are not followed: a 3xx target has not been through SsrfValidator, and
            // following it blindly would hand an attacker the internal address they were denied.
            throw new IllegalArgumentException(
                    "Fetching the URL returned HTTP " + response.statusCode()
                            + (response.statusCode() / 100 == 3
                                    ? " (redirects are not followed — supply the final URL)" : ""));
        }

        String contentType = response.headers().firstValue("content-type").orElse("")
                .toLowerCase(Locale.ROOT);
        if (!contentType.isBlank() && !contentType.contains("html") && !contentType.contains("text/plain")) {
            throw new IllegalArgumentException(
                    "The URL is not an HTML page (Content-Type: " + contentType + "). "
                            + "Upload the file directly instead.");
        }

        String body = response.body();
        if (body != null && body.length() > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException("The page is too large to ingest (limit "
                    + (MAX_CONTENT_BYTES / 1024 / 1024) + " MB)");
        }

        DocumentParserService.WebPageContent page = parserService.parseWebPage(body, url);
        if (page.text() == null || page.text().isBlank()) {
            throw new IllegalArgumentException("No readable text found at " + url);
        }

        // The URL is the source id, and KbTextIngestService replaces a source's previous document,
        // so re-adding the same URL refreshes it in place rather than piling up duplicates.
        String docPid = textIngestService.ingestText(
                tenantId, kbPid, "url", url, page.title(), page.text());
        if (docPid == null) {
            throw new IllegalArgumentException("Knowledge base not found: " + kbPid);
        }

        log.info("Ingested URL into kb={}: {} ({} chars)", kbPid, url, page.text().length());
        return docPid;
    }
}
