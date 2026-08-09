package com.auraboot.framework.authoring.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Iterator;
import java.util.Map;

import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/** Save-time sanitizer; renderers must still sanitize again at the runtime boundary. */
@Component
public class AuthoringContentSanitizer {

    private static final Safelist RICH_TEXT = Safelist.relaxed()
            .removeTags("style")
            .addProtocols("a", "href", "http", "https")
            .addProtocols("img", "src", "http", "https");

    private static final Document.OutputSettings OUTPUT = new Document.OutputSettings()
            .prettyPrint(false);

    public JsonNode sanitize(String propertyPath, JsonNode value) {
        if (value == null || value.isNull()) {
            return value;
        }
        if ("/props/content".equals(propertyPath)) {
            if (!value.isTextual()) {
                throw invalid("authoring.content.must-be-text");
            }
            return TextNode.valueOf(Jsoup.clean(value.asText(), "", RICH_TEXT, OUTPUT));
        }
        if ("/title".equals(propertyPath) || "/props/label".equals(propertyPath)) {
            return sanitizePlainText(value.deepCopy());
        }
        if ("/props/targetPage".equals(propertyPath)) {
            if (!value.isTextual() || !isSafeInternalTarget(value.asText())) {
                throw invalid("authoring.navigation.unsafe-target");
            }
        }
        rejectControlCharacters(value);
        return value.deepCopy();
    }

    private JsonNode sanitizePlainText(JsonNode value) {
        if (value.isTextual()) {
            return TextNode.valueOf(Jsoup.parse(value.asText()).text());
        }
        if (value.isObject()) {
            ObjectNode object = (ObjectNode) value;
            Iterator<Map.Entry<String, JsonNode>> fields = object.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!field.getValue().isTextual()) {
                    throw invalid("authoring.localized-text.invalid");
                }
                object.put(field.getKey(), Jsoup.parse(field.getValue().asText()).text());
            }
            return object;
        }
        throw invalid("authoring.text.invalid");
    }

    private void rejectControlCharacters(JsonNode node) {
        if (node.isTextual() && node.asText().chars().anyMatch(value -> value == 0)) {
            throw invalid("authoring.text.control-character");
        }
        if (node instanceof ObjectNode object) {
            object.elements().forEachRemaining(this::rejectControlCharacters);
        } else if (node instanceof ArrayNode array) {
            array.elements().forEachRemaining(this::rejectControlCharacters);
        }
    }

    private boolean isSafeInternalTarget(String target) {
        String trimmed = target.trim();
        return !trimmed.isEmpty()
                && trimmed.length() <= 240
                && !trimmed.startsWith("//")
                && !trimmed.contains("\\")
                && !trimmed.contains("..")
                && !trimmed.matches("(?i)^[a-z][a-z0-9+.-]*:.*")
                && trimmed.matches("[A-Za-z0-9_/?&=.#:-]+");
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(UNPROCESSABLE_ENTITY, reason);
    }
}
