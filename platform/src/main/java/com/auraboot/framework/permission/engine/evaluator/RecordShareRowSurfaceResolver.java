package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.engine.model.SharedRootReference;
import com.auraboot.framework.permission.service.RecordShareService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Resolves the declared shared-aggregate row surface for one child resource.
 *
 * <p>A record share is granted on an aggregate root; rows of child models that reference the
 * shared root belong to the same aggregate and must not be erased by the child resource's own
 * surface data scope (exactly the principle {@code NamedQueryPolicy.RootAccess} already applies
 * to named queries). Aggregation edges are <b>explicit</b>: the child model declares
 * {@code extension.recordShare = {"rootModel": …, "referenceField": …}}. A plain reference field
 * never implies aggregation — sharing a lookup target must not leak its referrers.
 *
 * <p>The root pids are resolved per call (per request) from {@link RecordShareService}, so
 * revocation takes effect immediately. Malformed declarations fail closed: the edge is skipped
 * (no grant), never widened.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecordShareRowSurfaceResolver {

    private final MetaModelService metaModelService;
    private final RecordShareService recordShareService;

    /**
     * @return the declared shared-root references whose roots currently have shares for the
     *         caller; empty when the resource declares no edge or no root is shared
     */
    public List<SharedRootReference> resolveSharedRootReferences(
            Long tenantId, Long memberId, String resource, String action) {
        if (tenantId == null || memberId == null || resource == null || resource.isBlank()) {
            return List.of();
        }
        Declaration declaration = readDeclaration(resource);
        if (declaration == null) {
            return List.of();
        }
        if (!declaredFieldExists(resource, declaration)) {
            return List.of();
        }
        List<String> rootPids = recordShareService.getSharedRecordPids(
                tenantId, declaration.rootModel(), memberId,
                MetaContext.getCurrentUserPid(), action);
        if (rootPids == null || rootPids.isEmpty()) {
            return List.of();
        }
        if (log.isDebugEnabled()) {
            log.debug("Shared row surface: {} rows referencing shared {} roots are in surface",
                    resource, declaration.rootModel());
        }
        return List.of(new SharedRootReference(declaration.referenceField(), rootPids));
    }

    /**
     * Read {@code extension.recordShare = {rootModel, referenceField}} from the model definition.
     */
    private Declaration readDeclaration(String resource) {
        try {
            MetaModelDTO model = metaModelService.findByCode(resource);
            if (model == null || model.getExtension() == null) {
                return null;
            }
            Object declared = model.getExtension().get("recordShare");
            if (!(declared instanceof Map<?, ?> spec)) {
                return null;
            }
            String rootModel = stringValue(spec.get("rootModel"));
            String referenceField = stringValue(spec.get("referenceField"));
            if (rootModel == null || referenceField == null || rootModel.equals(resource)) {
                return null;
            }
            return new Declaration(rootModel, referenceField);
        } catch (Exception e) {
            // codeql[java/log-injection] Resource codes are validated metadata identifiers and are logged as structured parameters only.
            log.debug("Could not load recordShare declaration for model {}: {}", resource, e.getMessage());
            return null;
        }
    }

    /**
     * Fail closed on unknown fields: a declaration naming a field the model does not have is a
     * stale/typo'd edge and grants nothing. The field itself may be a typed {@code reference} or
     * a pid-bearing string column — the grant predicate is value equality against the shared
     * root pids, so either storage shape is a faithful reference; a non-reference type only
     * downgrades to a warning.
     */
    private boolean declaredFieldExists(String resource, Declaration declaration) {
        for (FieldDefinition field : metaModelService.getModelFields(resource)) {
            if (!declaration.referenceField().equals(field.getCode())) {
                continue;
            }
            if (!"reference".equalsIgnoreCase(field.getDataType())) {
                log.warn("recordShare declaration on {} uses non-reference field {} ({});"
                        + " honoring the edge by value equality", resource, field.getCode(),
                        field.getDataType());
            } else {
                FieldDefinition.RefTarget refTarget = field.getRefTarget();
                if (refTarget != null
                        && !declaration.rootModel().equals(refTarget.getTargetEntity())) {
                    log.warn("recordShare declaration on {}.{} targets {} but the field references"
                            + " {}; honoring the edge by value equality", resource,
                            declaration.referenceField(), declaration.rootModel(),
                            refTarget.getTargetEntity());
                }
              }
            return true;
        }
        log.warn("recordShare declaration on {} references unknown field {}; ignoring the edge",
                resource, declaration.referenceField());
        return false;
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private record Declaration(String rootModel, String referenceField) {
    }
}
