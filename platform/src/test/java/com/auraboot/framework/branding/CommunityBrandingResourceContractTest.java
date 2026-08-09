package com.auraboot.framework.branding;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityBrandingResourceContractTest {

    private static final List<String> DEFAULT_PRINT_TEMPLATES = List.of(
            "print-templates/invoice.html",
            "print-templates/quote.html",
            "print-templates/delivery_note.html"
    );

    @Test
    void defaultPrintTemplatesKeepCommunityAttribution() throws IOException {
        for (String resource : DEFAULT_PRINT_TEMPLATES) {
            assertThat(readResource(resource))
                    .as(resource)
                    .contains(CommunityBranding.GENERATED_BY_TEXT);
        }
    }

    @Test
    void seededPrintTemplatesMatchTheCommunityOutputContract() throws IOException {
        String seed = readResource("database/seed-print-templates.sql");
        int occurrences = seed.split(
                Pattern.quote(CommunityBranding.GENERATED_BY_TEXT), -1).length - 1;

        assertThat(occurrences).isEqualTo(DEFAULT_PRINT_TEMPLATES.size());
    }

    private String readResource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
