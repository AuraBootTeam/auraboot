package com.auraboot.framework.authoring.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/** Re-sanitizes persisted snapshots at the release/runtime seam. */
@Component
public class AuthoringRuntimeSnapshotSanitizer {

    private static final List<SanitizedField> PROTECTED_PROPS = List.of(
            new SanitizedField("content", "/props/content"),
            new SanitizedField("label", "/props/label"),
            new SanitizedField("targetPage", "/props/targetPage"));

    private final AuthoringContentSanitizer contentSanitizer;

    public AuthoringRuntimeSnapshotSanitizer(AuthoringContentSanitizer contentSanitizer) {
        this.contentSanitizer = contentSanitizer;
    }

    public ObjectNode sanitize(JsonNode source) {
        if (!(source instanceof ObjectNode object)) {
            throw new IllegalArgumentException("authoring.runtime.snapshot-invalid");
        }
        ObjectNode copy = object.deepCopy();
        sanitizeNode(copy);
        return copy;
    }

    private void sanitizeNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            sanitizeTitle(object);
            sanitizeProps(object);
            object.elements().forEachRemaining(this::sanitizeNode);
        } else if (node.isArray()) {
            node.elements().forEachRemaining(this::sanitizeNode);
        }
    }

    private void sanitizeTitle(ObjectNode object) {
        JsonNode title = object.get("title");
        if (title != null) {
            object.set("title", contentSanitizer.sanitize("/title", title));
        }
    }

    private void sanitizeProps(ObjectNode object) {
        if (!(object.get("props") instanceof ObjectNode props)) {
            return;
        }
        for (SanitizedField field : PROTECTED_PROPS) {
            JsonNode value = props.get(field.name());
            if (value != null) {
                props.set(field.name(), contentSanitizer.sanitize(field.propertyPath(), value));
            }
        }
    }

    private record SanitizedField(String name, String propertyPath) {
    }
}
