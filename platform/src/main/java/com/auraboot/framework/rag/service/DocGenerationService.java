package com.auraboot.framework.rag.service;

import com.auraboot.framework.common.util.PathSafetyUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Auto-generate documentation from runtime metadata.
 * Produces markdown files for: model dictionary, field reference, command reference.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocGenerationService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Generate documentation files from DB metadata into the specified output directory.
     *
     * @param outputDir target directory for generated .md files
     * @return generation summary
     */
    public GenerationResult generate(String outputDir) throws IOException {
        Path outPath = PathSafetyUtils.normalizeAbsolute(Path.of(outputDir), "doc generation outputDir");
        Files.createDirectories(outPath);

        int modelCount = generateModelDictionary(outPath);
        int commandCount = generateCommandReference(outPath);
        int fieldSummary = generateFieldSummary(outPath);

        GenerationResult result = new GenerationResult(modelCount, commandCount, fieldSummary, outPath.toString());
        log.info("Doc generation complete: {}", result);
        return result;
    }

    /**
     * Generate model dictionary — one section per published model with fields and relationships.
     */
    private int generateModelDictionary(Path outPath) throws IOException {
        List<Map<String, Object>> models = jdbcTemplate.queryForList(
                "SELECT m.code, m.semantic_description, m.model_category, m.status, "
                + "m.table_name "
                + "FROM ab_meta_model m "
                + "WHERE m.status = 'published' AND m.is_current = TRUE "
                + "AND (m.deleted_flag IS NULL OR m.deleted_flag = FALSE) "
                + "ORDER BY m.model_category, m.code");

        if (models.isEmpty()) return 0;

        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: Model Dictionary\n");
        sb.append("lastGenerated: ").append(LocalDate.now()).append("\n");
        sb.append("---\n\n");
        sb.append("# Model Dictionary\n\n");
        sb.append("> Auto-generated from runtime metadata. Do not edit manually.\n\n");

        String currentCategory = "";
        for (Map<String, Object> model : models) {
            String category = getString(model, "model_category", "other");
            if (!category.equals(currentCategory)) {
                sb.append("\n## ").append(category).append("\n\n");
                currentCategory = category;
            }

            String code = getString(model, "code", "");
            String name = code;
            String desc = getString(model, "semantic_description", "");
            String table = getString(model, "table_name", "mt_" + code);

            sb.append("### ").append(name).append(" (`").append(code).append("`)\n\n");
            if (!desc.isBlank()) sb.append(desc).append("\n\n");
            sb.append("- **Table**: `").append(table).append("`\n");
            sb.append("- **Category**: ").append(category).append("\n");
            sb.append("- **Title Field**: `").append(getString(model, "title_field", "—")).append("`\n\n");

            // Fields for this model
            List<Map<String, Object>> fields = jdbcTemplate.queryForList(
                    "SELECT f.code, f.data_type "
                    + " "
                    + "FROM ab_meta_field f "
                    + "JOIN ab_meta_model_field_binding b ON f.id = b.field_id "
                    + "JOIN ab_meta_model m ON b.model_id = m.id "
                    + "WHERE m.code = ? "
                    + "AND (f.deleted_flag IS NULL OR f.deleted_flag = FALSE) "
                    + "ORDER BY f.code",
                    code);

            if (!fields.isEmpty()) {
                sb.append("| Field | Type | Description |\n");
                sb.append("|-------|------|-------------|\n");
                for (Map<String, Object> field : fields) {
                    String fCode = getString(field, "code", "");
                    String fType = getString(field, "data_type", "string");
                    String fDesc = "";
                    String extra = "";
                    sb.append("| `").append(fCode).append("` | ").append(fType)
                      .append(" | ").append(fDesc).append(" |\n");
                }
                sb.append("\n");
            }
        }

        Files.writeString(PathSafetyUtils.requireSafeChild(outPath, "model-dictionary.md", "model dictionary output"), sb.toString());
        return models.size();
    }

    /**
     * Generate command reference — lists all commands grouped by model.
     */
    private int generateCommandReference(Path outPath) throws IOException {
        List<Map<String, Object>> commands = jdbcTemplate.queryForList(
                "SELECT c.code, c.display_name, c.description, "
                + "c.model_code, c.status "
                + "FROM ab_command_definition c "
                + "WHERE c.status = 'published' AND c.is_current = TRUE "
                + "AND (c.deleted_flag IS NULL OR c.deleted_flag = FALSE) "
                + "AND c.is_current = TRUE ORDER BY c.model_code, c.code");

        if (commands.isEmpty()) return 0;

        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: Command Reference\n");
        sb.append("lastGenerated: ").append(LocalDate.now()).append("\n");
        sb.append("---\n\n");
        sb.append("# Command Reference\n\n");
        sb.append("> Auto-generated from runtime metadata. Do not edit manually.\n\n");
        sb.append("| Command | Model | Description |\n");
        sb.append("|---------|-------|-------------|\n");

        for (Map<String, Object> cmd : commands) {
            sb.append("| `").append(getString(cmd, "code", ""))
              .append("` | `").append(getString(cmd, "model_code", ""))
              .append("` | ").append("")
              .append(" | ").append(getString(cmd, "description", ""))
              .append(" |\n");
        }

        Files.writeString(PathSafetyUtils.requireSafeChild(outPath, "command-reference.md", "command reference output"), sb.toString());
        return commands.size();
    }

    /**
     * Generate field summary — aggregated statistics by data type.
     */
    private int generateFieldSummary(Path outPath) throws IOException {
        List<Map<String, Object>> stats = jdbcTemplate.queryForList(
                "SELECT f.data_type, COUNT(*) AS cnt "
                + "FROM ab_meta_field f "
                + "WHERE (f.deleted_flag IS NULL OR f.deleted_flag = FALSE) "
                + "GROUP BY f.data_type ORDER BY cnt DESC");

        int totalFields = stats.stream()
                .mapToInt(s -> ((Number) s.get("cnt")).intValue())
                .sum();

        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: Field Type Summary\n");
        sb.append("lastGenerated: ").append(LocalDate.now()).append("\n");
        sb.append("---\n\n");
        sb.append("# Field Type Summary\n\n");
        sb.append("> Total fields: ").append(totalFields).append("\n\n");
        sb.append("| Data Type | Count |\n");
        sb.append("|-----------|-------|\n");

        for (Map<String, Object> row : stats) {
            sb.append("| ").append(getString(row, "data_type", "unknown"))
              .append(" | ").append(row.get("cnt")).append(" |\n");
        }

        Files.writeString(PathSafetyUtils.requireSafeChild(outPath, "field-summary.md", "field summary output"), sb.toString());
        return totalFields;
    }

    private String getString(Map<String, Object> map, String key, String fallback) {
        Object val = map.get(key);
        if (val == null) return fallback;
        String s = val.toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    public record GenerationResult(int models, int commands, int fields, String outputDir) {}
}
