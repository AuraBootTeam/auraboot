package com.auraboot.framework.branding;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Validates the shared deployment appearance before persistence or publication. */
public final class AuthAppearanceValidator {
    private static final JsonSchema SCHEMA = loadSchema();

    private AuthAppearanceValidator() {}

    public static void validate(JsonNode appearance) {
        Set<ValidationMessage> errors = SCHEMA.validate(appearance);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid authAppearance: " + errors);
        }
    }

    private static JsonSchema loadSchema() {
        try (InputStream input = AuthAppearanceValidator.class.getResourceAsStream(
                "/branding/auth-appearance.schema.json")) {
            if (input == null) {
                throw new IllegalStateException("Missing auth appearance schema resource");
            }
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read auth appearance schema", exception);
        }
    }
}
