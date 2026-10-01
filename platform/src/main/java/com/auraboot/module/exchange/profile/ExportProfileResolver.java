package com.auraboot.module.exchange.profile;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.DataExportRequest;
import com.auraboot.framework.meta.dto.DynamicDataExportRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resolves and validates the fail-closed server contract for model-list exports. */
@Service
@RequiredArgsConstructor
public class ExportProfileResolver {
    private final MetaModelService metaModelService;

    public ExportProfile requireEnabled(String modelCode) {
        ModelDefinition model = metaModelService.getModelDefinition(modelCode)
                .orElseThrow(() -> new BusinessException("Model not found: " + modelCode));
        Map<String, Object> extension = model.getExtension();
        Map<String, Object> raw = extension == null ? null : asMap(extension.get("exportPolicy"));
        if (raw != null && Boolean.FALSE.equals(raw.get("enabled"))) {
            throw new BusinessException("Export is disabled for model: " + modelCode);
        }

        Set<DynamicDataExportRequest.Scope> scopes = scopes(raw == null ? null : raw.get("scopes"));
        if (scopes.isEmpty()) {
            scopes = Set.of(DynamicDataExportRequest.Scope.selected,
                    DynamicDataExportRequest.Scope.filtered);
        }
        Set<DataExportRequest.ExportFormat> formats = formats(raw == null ? null : raw.get("formats"));
        if (formats.isEmpty()) {
            formats = Set.of(DataExportRequest.ExportFormat.EXCEL, DataExportRequest.ExportFormat.CSV);
        }

        List<String> fields = strings(raw == null ? null : raw.get("fields"));
        if (new LinkedHashSet<>(fields).size() != fields.size()) {
            throw new BusinessException("Export profile fields must not contain duplicates: " + modelCode);
        }
        if (!fields.isEmpty()) {
            Set<String> declared = new LinkedHashSet<>();
            List<FieldDefinition> modelFields = metaModelService.getModelFields(modelCode);
            if (modelFields != null) modelFields.forEach(field -> declared.add(field.getCode()));
            List<String> unknown = fields.stream().filter(field -> !declared.contains(field)).toList();
            if (!unknown.isEmpty()) {
                throw new BusinessException("Unknown export profile fields for model " + modelCode
                        + ": " + String.join(",", unknown));
            }
        }

        return ExportProfile.builder()
                .code(string(raw == null ? null : raw.get("code"), modelCode + ":default-export"))
                .modelCode(modelCode)
                .enabled(true)
                .scopes(Set.copyOf(scopes))
                .formats(Set.copyOf(formats))
                .fields(List.copyOf(fields))
                .build();
    }

    public List<String> resolveFields(ExportProfile profile, List<String> requestedFields) {
        if (requestedFields == null || requestedFields.isEmpty()) {
            return profile.getFields().isEmpty() ? null : profile.getFields();
        }
        if (!profile.getFields().isEmpty()) {
            List<String> forbidden = requestedFields.stream()
                    .filter(field -> !profile.getFields().contains(field)).distinct().toList();
            if (!forbidden.isEmpty()) {
                throw new BusinessException("Fields are not allowed by export profile: "
                        + String.join(",", forbidden));
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(requestedFields));
    }

    private static Set<DynamicDataExportRequest.Scope> scopes(Object raw) {
        LinkedHashSet<DynamicDataExportRequest.Scope> result = new LinkedHashSet<>();
        for (String value : strings(raw)) {
            try {
                DynamicDataExportRequest.Scope scope = DynamicDataExportRequest.Scope.valueOf(
                        value.toLowerCase(Locale.ROOT));
                if (!Set.of(DynamicDataExportRequest.Scope.selected,
                        DynamicDataExportRequest.Scope.filtered).contains(scope)) {
                    throw new IllegalArgumentException();
                }
                result.add(scope);
            } catch (IllegalArgumentException invalid) {
                throw new BusinessException("Unsupported export scope: " + value);
            }
        }
        return result;
    }

    private static Set<DataExportRequest.ExportFormat> formats(Object raw) {
        LinkedHashSet<DataExportRequest.ExportFormat> result = new LinkedHashSet<>();
        for (String value : strings(raw)) {
            String normalized = value.equalsIgnoreCase("xlsx") ? "EXCEL" : value.toUpperCase(Locale.ROOT);
            try {
                DataExportRequest.ExportFormat format = DataExportRequest.ExportFormat.valueOf(normalized);
                if (!Set.of(DataExportRequest.ExportFormat.EXCEL,
                        DataExportRequest.ExportFormat.CSV,
                        DataExportRequest.ExportFormat.JSON).contains(format)) {
                    throw new IllegalArgumentException();
                }
                result.add(format);
            } catch (IllegalArgumentException invalid) {
                throw new BusinessException("Unsupported export format: " + value);
            }
        }
        return result;
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof Collection<?> values)) return List.of();
        return values.stream().filter(java.util.Objects::nonNull)
                .map(Object::toString).map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    private static String string(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }
}
