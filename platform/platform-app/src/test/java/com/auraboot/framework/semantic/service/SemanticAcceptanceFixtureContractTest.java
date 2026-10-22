package com.auraboot.framework.semantic.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Hermetic source-policy guard; it proves retention intent, not DB execution. */
class SemanticAcceptanceFixtureContractTest {
    private static final Path SOURCES = Path.of("src/test/java/com/auraboot/framework/semantic/service");

    @Test
    void acceptanceTeardownRetainsOwnedDatabaseEvidence() throws Exception {
        Pattern teardown = Pattern.compile("@(?:org\\.junit\\.jupiter\\.api\\.)?AfterAll\\s+void\\s+\\w+\\s*\\([^)]*\\)\\s*\\{");
        for (String name : List.of("SemanticMetricAlertIT", "SemanticPreaggIT", "SemanticUsageSummaryIT",
                "SemanticChatBiEvalIT", "SemanticQuestionResolverIT")) {
            String source = Files.readString(SOURCES.resolve(name + ".java"));
            var matches = teardown.matcher(source);
            int found = 0;
            while (matches.find()) {
                int depth = 1;
                int end = matches.end();
                while (depth > 0 && end < source.length()) {
                    char character = source.charAt(end++);
                    if (character == '{') depth++;
                    if (character == '}') depth--;
                }
                assertThat(depth).as("complete teardown: %s", name).isZero();
                String body = source.substring(matches.end(), end);
                assertThat(body).as("retained acceptance evidence: %s", name)
                        .doesNotContain("DELETE FROM", "TRUNCATE", "DROP MATERIALIZED VIEW",
                                "jdbc.update(", "jdbc.execute(", "preaggService.delete(", "alertService.delete(");
                found++;
            }
            assertThat(found).as("context teardown must be explicit: %s", name).isGreaterThan(0);
        }
    }

    @Test
    void preaggCasesDoNotEraseAnotherCasesRowsOrMetadata() throws Exception {
        String source = Files.readString(SOURCES.resolve("SemanticPreaggIT.java"));
        assertThat(source).doesNotContain("DELETE FROM ab_object_alias", "DELETE FROM ab_meta_model");
    }
}
