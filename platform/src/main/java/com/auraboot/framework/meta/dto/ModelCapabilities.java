package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.List;

/**
 * Capabilities declaration for a model (physical or virtual).
 *
 * Naming convention: runtime/validation/UI all use
 * `sort` / `filter` / `paginate` — never `supportsSort` / `supportsFilter` / etc.
 *
 * Whitelist fields (`sortableFields` / `filterableFields`) are the runtime truth.
 * Per-field boolean flags on ResolvedField are editor input only and get
 * normalized into these whitelists at MetaModelService save time.
 */
@Value
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ModelCapabilities {

    boolean list;
    boolean detail;
    boolean create;
    boolean update;
    boolean delete;
    boolean bulkDelete;
    boolean export;
    boolean sort;
    boolean filter;
    boolean paginate;

    @Builder.Default
    List<String> sortableFields = Collections.emptyList();

    @Builder.Default
    List<String> filterableFields = Collections.emptyList();

    /** If null or blank, falls back to primaryKey at resolve time. */
    String detailKeyField;

    public static ModelCapabilities empty() {
        return ModelCapabilities.builder().build();
    }

    /** Full CRUD + sort/filter/paginate/export, empty whitelists (to be populated from fields at save time). */
    public static ModelCapabilities fullPhysical() {
        return ModelCapabilities.builder()
            .list(true).detail(true)
            .create(true).update(true).delete(true).bulkDelete(true)
            .export(true).sort(true).filter(true).paginate(true)
            .build();
    }

    /** Phase-1 virtual model default: read-only; list/detail/export/sort/filter/paginate on. */
    public static ModelCapabilities virtualReadOnly() {
        return ModelCapabilities.builder()
            .list(true).detail(true)
            .export(true).sort(true).filter(true).paginate(true)
            .build();
    }

    public boolean canSortBy(String fieldCode) {
        return sort && sortableFields != null && sortableFields.contains(fieldCode);
    }

    public boolean canFilterBy(String fieldCode) {
        return filter && filterableFields != null && filterableFields.contains(fieldCode);
    }

    public String resolveDetailKeyField(String primaryKey) {
        return detailKeyField != null && !detailKeyField.isBlank()
            ? detailKeyField : primaryKey;
    }

    /**
     * Custom builder that defensively copies whitelist inputs as immutable
     * snapshots. {@code @Value} freezes the field reference but not list
     * contents; without this copy, a caller retaining the original mutable
     * list could mutate the whitelist post-construction and bypass the
     * runtime-truth invariant.
     */
    public static class ModelCapabilitiesBuilder {
        public ModelCapabilitiesBuilder sortableFields(List<String> fields) {
            this.sortableFields$value = fields == null ? Collections.emptyList() : List.copyOf(fields);
            this.sortableFields$set = true;
            return this;
        }

        public ModelCapabilitiesBuilder filterableFields(List<String> fields) {
            this.filterableFields$value = fields == null ? Collections.emptyList() : List.copyOf(fields);
            this.filterableFields$set = true;
            return this;
        }
    }
}
