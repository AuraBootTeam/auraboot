package com.auraboot.framework.cswidget;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Serves the embeddable customer-service widget.
 *
 * <p>This is the one thing the {@code @auraboot/track} precedent never had: a bundle nobody serves
 * is a bundle nobody can embed. The customer's page carries a single tag —
 *
 * <pre>{@code <script src="https://<host>/api/public/cs/widget.js" data-site-key="csk_..." async></script>}</pre>
 *
 * <p>and the widget reads its own configuration off that tag and calls back to this same origin.
 * Nothing is substituted into the file: the site key is public and travels in the tag, and the
 * API base is derived from the script's own {@code src}, so the artifact is byte-identical for
 * every customer and can be cached hard.
 *
 * <p>Lives under {@code /api/public/cs/**}, which is already anonymous (OSS {@code WhiteList}) and
 * already carries the relaxed CORS rule the widget needs on a third-party origin.
 */
@Slf4j
@RestController
public class CsWidgetController {

    private static final String BUNDLE = "static/cs/aura-cs.global.js";

    @GetMapping(value = "/api/public/cs/widget.js", produces = "application/javascript")
    public ResponseEntity<String> widget() {
        ClassPathResource resource = new ClassPathResource(BUNDLE);
        if (!resource.exists()) {
            // Fail loudly rather than answering 200 with an empty body: a silently missing widget
            // looks to the customer like their site is broken, with nothing in any log to explain it.
            log.error("Widget bundle {} is not on the classpath — was the cs-widget package built?", BUNDLE);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "widget_bundle_missing");
        }

        try (InputStream in = resource.getInputStream()) {
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(15, TimeUnit.MINUTES).cachePublic())
                    .body(script);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "widget_bundle_unreadable", e);
        }
    }
}
