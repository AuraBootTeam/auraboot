package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.authoring.workspace.AuthoringActiveReleaseResolver.ActiveRelease;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.dto.PageSchemaRuntimeDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.FORBIDDEN;

/** Resolves a published PageSchema baseline against its active immutable authoring release. */
@Component
public class AuthoringRuntimePageMaterializer {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };
    private static final TypeReference<List<Object>> OBJECT_LIST = new TypeReference<>() { };

    private final AuthoringActiveReleaseResolver activeReleaseResolver;
    private final ObjectMapper objectMapper;

    public AuthoringRuntimePageMaterializer(
            AuthoringActiveReleaseResolver activeReleaseResolver,
            ObjectMapper objectMapper) {
        this.activeReleaseResolver = activeReleaseResolver;
        this.objectMapper = objectMapper;
    }

    public PageSchemaDTO materialize(PageSchemaDTO baseline) {
        if (baseline == null) {
            return null;
        }
        long tenantId = requiredTenantId();
        long envId = requiredEnvironmentId();
        ActiveRelease active = activeReleaseResolver.findByResource(
                tenantId, envId, "PAGE_SCHEMA", baseline.getPid());
        if (active == null) {
            baseline.setRuntime(legacyRuntime(baseline));
            return baseline;
        }

        JsonNode snapshot = active.snapshot();
        requireIdentity(snapshot, "pid", baseline.getPid());
        requireIdentity(snapshot, "pageKey", baseline.getPageKey());
        requireIdentity(snapshot, "modelCode", baseline.getModelCode());
        applySnapshot(baseline, snapshot);
        baseline.setRuntime(new PageSchemaRuntimeDTO(
                "AUTHORING_RELEASE",
                active.releasePid(),
                active.channelVersion(),
                active.sourceVersion(),
                active.snapshotChecksum(),
                releaseCacheKey(active)));
        return baseline;
    }

    private void applySnapshot(PageSchemaDTO target, JsonNode snapshot) {
        setText(snapshot, "name", target::setName);
        setText(snapshot, "description", target::setDescription);
        setText(snapshot, "kind", target::setKind);
        setText(snapshot, "profile", target::setProfile);
        if (snapshot.has("schemaVersion") && snapshot.get("schemaVersion").canConvertToInt()) {
            target.setSchemaVersion(snapshot.get("schemaVersion").intValue());
        }
        if (snapshot.has("isTemplate") && snapshot.get("isTemplate").isBoolean()) {
            target.setIsTemplate(snapshot.get("isTemplate").booleanValue());
        }
        if (snapshot.path("title").isObject()) {
            target.setTitle(objectMapper.convertValue(snapshot.get("title"), OBJECT_MAP));
        }
        if (snapshot.path("layout").isObject()) {
            target.setLayout(objectMapper.convertValue(snapshot.get("layout"), OBJECT_MAP));
        }
        if (snapshot.path("blocks").isArray()) {
            target.setBlocks(objectMapper.convertValue(snapshot.get("blocks"), OBJECT_LIST));
        }
    }

    private void setText(JsonNode snapshot, String field, java.util.function.Consumer<String> setter) {
        if (snapshot.has(field) && snapshot.get(field).isTextual()) {
            setter.accept(snapshot.get(field).textValue());
        }
    }

    private void requireIdentity(JsonNode snapshot, String field, String expected) {
        JsonNode actualNode = snapshot.get(field);
        String actual = actualNode != null && actualNode.isTextual() ? actualNode.textValue() : null;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new DataRetrievalFailureException(
                    "Authoring release identity mismatch: " + field);
        }
    }

    private PageSchemaRuntimeDTO legacyRuntime(PageSchemaDTO baseline) {
        long sourceVersion = baseline.getRowVersion() == null
                ? defaultVersion(baseline.getVersion())
                : baseline.getRowVersion().longValue();
        return new PageSchemaRuntimeDTO(
                "PAGE_SCHEMA",
                null,
                0,
                sourceVersion,
                null,
                "page-schema:" + baseline.getPid() + ":" + sourceVersion);
    }

    private long defaultVersion(Integer version) {
        return version == null ? 1L : version.longValue();
    }

    private String releaseCacheKey(ActiveRelease active) {
        return "authoring-release:" + active.releasePid()
                + ":" + active.channelVersion()
                + ":" + active.snapshotChecksum();
    }

    private long requiredTenantId() {
        MetaContext context = MetaContext.get();
        if (context.getTenantId() == null) {
            throw new ResponseStatusException(FORBIDDEN, "authoring.context.incomplete");
        }
        return context.getTenantId();
    }

    private long requiredEnvironmentId() {
        Long envId = MetaContext.getCurrentEnvironmentId();
        if (envId == null) {
            throw new ResponseStatusException(FORBIDDEN, "authoring.context.incomplete");
        }
        return envId;
    }
}
