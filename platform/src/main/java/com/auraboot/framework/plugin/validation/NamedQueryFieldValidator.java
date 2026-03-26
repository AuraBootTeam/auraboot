package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.meta.dto.NamedQueryFieldRequest;
import com.auraboot.framework.plugin.dto.imports.NamedQueryDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static com.auraboot.framework.plugin.validation.PluginValidationMessage.error;
import static com.auraboot.framework.plugin.validation.PluginValidationMessage.warning;

/**
 * S-NQF: Validates named query field definitions in plugin manifests.
 * <p>
 * Checks:
 * - Each field has required properties: fieldCode, columnExpr, dataType
 * - dataType is one of the allowed values
 * - fieldCode matches naming pattern
 * - No duplicate field codes within a query
 */
@Slf4j
@Component
public class NamedQueryFieldValidator implements PluginValidator {

    private static final Pattern FIELD_CODE_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");
    private static final Set<String> VALID_DATA_TYPES = Set.of(
            "string", "number", "date", "boolean", "json", "array"
    );

    @Override
    public String category() {
        return "semantic";
    }

    @Override
    public List<PluginValidationMessage> validate(PluginValidationContext ctx) {
        List<PluginValidationMessage> messages = new ArrayList<>();
        PluginManifestExtended manifest = ctx.getManifest();

        List<NamedQueryDefinitionDTO> queries = manifest.getNamedQueries();
        if (queries == null || queries.isEmpty()) {
            return messages;
        }

        for (int i = 0; i < queries.size(); i++) {
            NamedQueryDefinitionDTO query = queries.get(i);
            if (query == null) continue;

            String queryCode = query.getCode() != null ? query.getCode() : "unnamed";
            String basePath = "namedQueries[" + i + "](" + queryCode + ")";

            List<NamedQueryFieldRequest> fields = query.getFields();
            if (fields == null || fields.isEmpty()) {
                continue;
            }

            java.util.Set<String> seenCodes = new java.util.HashSet<>();

            for (int j = 0; j < fields.size(); j++) {
                NamedQueryFieldRequest field = fields.get(j);
                if (field == null) continue;

                String fieldPath = basePath + ".fields[" + j + "]";
                String fieldCode = field.getFieldCode();

                // fieldCode is required
                if (fieldCode == null || fieldCode.isBlank()) {
                    messages.add(error("S-NQF-CODE", category(), fieldPath,
                            "Named query field is missing 'fieldCode' (or 'code')"));
                    continue;
                }

                // fieldCode format
                if (!FIELD_CODE_PATTERN.matcher(fieldCode).matches()) {
                    messages.add(error("S-NQF-CODE-FMT", category(), fieldPath,
                            "Field code '" + fieldCode + "' must start with a letter and contain only [a-zA-Z0-9_]"));
                }

                // duplicate check
                if (!seenCodes.add(fieldCode)) {
                    messages.add(error("S-NQF-DUP", category(), fieldPath,
                            "Duplicate field code '" + fieldCode + "' in query '" + queryCode + "'"));
                }

                // columnExpr is required
                if (field.getColumnExpr() == null || field.getColumnExpr().isBlank()) {
                    messages.add(error("S-NQF-EXPR", category(), fieldPath,
                            "Field '" + fieldCode + "' is missing 'columnExpr'. "
                                    + "Add \"columnExpr\": \"" + fieldCode + "\" if it matches the SQL alias"));
                }

                // dataType is required
                if (field.getDataType() == null || field.getDataType().isBlank()) {
                    messages.add(error("S-NQF-TYPE", category(), fieldPath,
                            "Field '" + fieldCode + "' is missing 'dataType'. "
                                    + "Must be one of: " + VALID_DATA_TYPES));
                } else if (!VALID_DATA_TYPES.contains(field.getDataType().toLowerCase())) {
                    messages.add(error("S-NQF-TYPE-VAL", category(), fieldPath,
                            "Field '" + fieldCode + "' has invalid dataType '" + field.getDataType()
                                    + "'. Must be one of: " + VALID_DATA_TYPES));
                }
            }
        }

        return messages;
    }
}
